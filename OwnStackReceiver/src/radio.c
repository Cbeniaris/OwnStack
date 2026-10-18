#include <errno.h>

#include <zephyr/kernel.h>

#include <nrf_gzll.h>
#include <gzll_glue.h>

#include "radio.h"

/*
 * Link settings. The mouse must use the same pipe, base address and prefix byte.
 * Everything else (2 Mbit, 0 dBm, channel table, timeslot period) is left at the
 * Gazell defaults, which are identical on both ends.
*/
#define RADIO_PIPE            0
#define RADIO_BASE_ADDRESS_0  0xA7B3C5D1
#define RADIO_PREFIX_BYTE_0   0x5E

// Received reports waiting for the main loop. Reports of the wrong size are dropped.
#define RADIO_QUEUE_LENGTH    32

K_MSGQ_DEFINE(radioMsgq, RADIO_REPORT_SIZE, RADIO_QUEUE_LENGTH, 1);

// Received keyboard reports waiting for the keyboard thread. Every report is the full key state.
#define RADIO_KEYBOARD_QUEUE_LENGTH 16

K_MSGQ_DEFINE(radioKeyboardMsgq, RADIO_KEYBOARD_REPORT_SIZE, RADIO_KEYBOARD_QUEUE_LENGTH, 1);

// Received config frames (responses from the mouse) waiting for the config thread
#define RADIO_FRAME_QUEUE_LENGTH 4

K_MSGQ_DEFINE(radioFrameMsgq, RADIO_FRAME_SIZE, RADIO_FRAME_QUEUE_LENGTH, 1);

/*
 * How long a request from the PC waits for the mouse. Gazell can only send to the mouse in the
 * acknowledgment of a packet the mouse transmits, so a request sits in the TX FIFO until the
 * mouse transmits. If the mouse has not picked it up after this time, it is removed.
*/
#define RADIO_REQUEST_HOLD_MS 2000

static bool radioReady = false;

// Guards the TX FIFO and requestDeadline: the USB thread loads requests, the system work queue removes expired ones
K_MUTEX_DEFINE(requestMutex);

// Uptime in milliseconds after which the waiting request is removed
static int64_t requestDeadline = 0;

// Removes the waiting request once its hold time is over
static void radioRequestExpired(struct k_work *work)
{
    ARG_UNUSED(work);

    k_mutex_lock(&requestMutex, K_FOREVER);

    // a newer request may have been loaded while this waited for the lock, leave that one alone
    if (k_uptime_get() >= requestDeadline) {
        nrf_gzll_flush_tx_fifo(RADIO_PIPE);
    }

    k_mutex_unlock(&requestMutex);
}

K_WORK_DELAYABLE_DEFINE(requestExpiryWork, radioRequestExpired);

int radioInit(void)
{
    if (!gzll_glue_init()) {
        return -EIO;
    }

    if (!nrf_gzll_init(NRF_GZLL_MODE_HOST)) {
        return -EIO;
    }

    // settings can only be changed while Gazell is disabled, which it is after init
    if (!nrf_gzll_set_base_address_0(RADIO_BASE_ADDRESS_0)) {
        return -EIO;
    }

    if (!nrf_gzll_set_address_prefix_byte(RADIO_PIPE, RADIO_PREFIX_BYTE_0)) {
        return -EIO;
    }

    if (!nrf_gzll_enable()) {
        return -EIO;
    }

    radioReady = true;
    return 0;
}

int radioReceive(uint8_t *report, k_timeout_t timeout)
{
    return k_msgq_get(&radioMsgq, report, timeout);
}

int radioReceiveKeyboard(uint8_t *report, k_timeout_t timeout)
{
    return k_msgq_get(&radioKeyboardMsgq, report, timeout);
}

int radioReceiveFrame(uint8_t *frame, k_timeout_t timeout)
{
    return k_msgq_get(&radioFrameMsgq, frame, timeout);
}

int radioSendRequest(const uint8_t *frame)
{
    if (!radioReady) {
        return -ENODEV;
    }

    k_mutex_lock(&requestMutex, K_FOREVER);

    // Discard any request still waiting in the transmit FIFO.
    bool flushed = nrf_gzll_flush_tx_fifo(RADIO_PIPE);

    // Always attempt to queue the new request.
    bool queued = nrf_gzll_add_packet_to_tx_fifo(
        RADIO_PIPE, frame, RADIO_FRAME_SIZE
    );

    printk("radioSendRequest: flushed=%d queued=%d\n",
           flushed, queued);

    if (queued) {
        requestDeadline = k_uptime_get() + RADIO_REQUEST_HOLD_MS;
        k_work_reschedule(&requestExpiryWork,
                          K_MSEC(RADIO_REQUEST_HOLD_MS));
    }

    k_mutex_unlock(&requestMutex);

    return queued ? 0 : -EIO;
}

/*
 * Called by Gazell when a packet has arrived. Every packet in the RX FIFO is moved
 * into a message queue so the main loop (mouse reports), the keyboard thread (keyboard
 * reports) or the config thread (config frames) can pick it up. The size tells them apart;
 * packets of any other size are dropped.
 * k_msgq_put with K_NO_WAIT is safe from this context; a full queue drops the packet.
*/
void nrf_gzll_host_rx_data_ready(uint32_t pipe, nrf_gzll_host_rx_info_t rxInfo)
{
    ARG_UNUSED(rxInfo);

    uint8_t payload[NRF_GZLL_CONST_MAX_PAYLOAD_LENGTH];
    uint32_t length = NRF_GZLL_CONST_MAX_PAYLOAD_LENGTH;

    while (nrf_gzll_fetch_packet_from_rx_fifo(pipe, payload, &length)) {
        if (length == RADIO_REPORT_SIZE) {
            k_msgq_put(&radioMsgq, payload, K_NO_WAIT);
        } else if (length == RADIO_KEYBOARD_REPORT_SIZE) {
            k_msgq_put(&radioKeyboardMsgq, payload, K_NO_WAIT);
        } else if (length == RADIO_FRAME_SIZE) {
            k_msgq_put(&radioFrameMsgq, payload, K_NO_WAIT);
        }

        length = NRF_GZLL_CONST_MAX_PAYLOAD_LENGTH;
    }
}

/*
 * The remaining Gazell callbacks. The link layer requires all of them to exist,
 * even the ones for the other role.
*/
void nrf_gzll_device_tx_success(uint32_t pipe, nrf_gzll_device_tx_info_t txInfo)
{
    ARG_UNUSED(pipe);
    ARG_UNUSED(txInfo);
}

void nrf_gzll_device_tx_failed(uint32_t pipe, nrf_gzll_device_tx_info_t txInfo)
{
    ARG_UNUSED(pipe);
    ARG_UNUSED(txInfo);
}

void nrf_gzll_disabled(void)
{
}
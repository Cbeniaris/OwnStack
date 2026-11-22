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

/*
 * Diagnostics. The counters below are filled by the Gazell receive callback and printed
 * (then cleared) once per second by statsWork, only when something arrived in that second.
 * The Gazell settings are printed at startup and again every RADIO_STATS_CONFIG_EVERY lines.
*/
#define RADIO_STATS_PERIOD_MS       1000
#define RADIO_STATS_CONFIG_EVERY    20

// Times the receive callback ran, and packets received by size
static atomic_t statsCallbacks = ATOMIC_INIT(0);
static atomic_t statsMouse = ATOMIC_INIT(0);
static atomic_t statsKeyboard = ATOMIC_INIT(0);
static atomic_t statsFrame = ATOMIC_INIT(0);

// Packets of any other size (the mouse's one byte poll packets) and packets dropped because a queue was full
static atomic_t statsOther = ATOMIC_INIT(0);
static atomic_t statsDropped = ATOMIC_INIT(0);

// Number of statistics lines printed so far, decides when the Gazell settings are printed again
static uint32_t statsLinesPrinted = 0;

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

// the work item and its handler refer to each other, so the handler is declared first
static void radioStatsReport(struct k_work *work);

K_WORK_DELAYABLE_DEFINE(statsWork, radioStatsReport);

// Prints the Gazell settings that decide how fast packets can be received
static void radioPrintConfig(void)
{
    nrf_gzll_device_channel_selection_policy_t policy = nrf_gzll_get_device_channel_selection_policy();

    printk("Gazell config: timeslot period=%u us, timeslots per channel=%u, out of sync=%u, sync lifetime=%u, max tx attempts=%u\n",
           (unsigned int)nrf_gzll_get_timeslot_period(),
           (unsigned int)nrf_gzll_get_timeslots_per_channel(),
           (unsigned int)nrf_gzll_get_timeslots_per_channel_when_device_out_of_sync(),
           (unsigned int)nrf_gzll_get_sync_lifetime(),
           (unsigned int)nrf_gzll_get_max_tx_attempts());

    printk("Gazell config: channel policy=%s, channels=%u, datarate=%d, tx power=%d\n",
           (policy == NRF_GZLL_DEVICE_CHANNEL_SELECTION_POLICY_USE_CURRENT) ? "use current" : "use successful",
           (unsigned int)nrf_gzll_get_channel_table_size(),
           (int)nrf_gzll_get_datarate(),
           (int)nrf_gzll_get_tx_power());
}

// Prints and clears the counters of the last second, then schedules itself again
static void radioStatsReport(struct k_work *work)
{
    ARG_UNUSED(work);

    unsigned int callbacks = (unsigned int)atomic_set(&statsCallbacks, 0);
    unsigned int mouse = (unsigned int)atomic_set(&statsMouse, 0);
    unsigned int keyboard = (unsigned int)atomic_set(&statsKeyboard, 0);
    unsigned int frame = (unsigned int)atomic_set(&statsFrame, 0);
    unsigned int other = (unsigned int)atomic_set(&statsOther, 0);
    unsigned int dropped = (unsigned int)atomic_set(&statsDropped, 0);

    // stay quiet while nothing is arriving
    if ((callbacks | mouse | keyboard | frame | other | dropped) != 0) {
        if ((statsLinesPrinted % RADIO_STATS_CONFIG_EVERY) == 0) {
            radioPrintConfig();
        }

        statsLinesPrinted++;

        printk("RADIO/s: callbacks=%u | mouse=%u keyboard=%u frame=%u other=%u | dropped=%u | mouseQueueUsed=%u\n",
               callbacks, mouse, keyboard, frame, other, dropped, (unsigned int)k_msgq_num_used_get(&radioMsgq));
    }

    k_work_reschedule(&statsWork, K_MSEC(RADIO_STATS_PERIOD_MS));
}

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

    // print the Gazell settings once at startup and start the once-per-second report
    radioPrintConfig();
    k_work_schedule(&statsWork, K_MSEC(RADIO_STATS_PERIOD_MS));

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

    // diagnostics: one run of this callback
    atomic_inc(&statsCallbacks);

    while (nrf_gzll_fetch_packet_from_rx_fifo(pipe, payload, &length)) {
        if (length == RADIO_REPORT_SIZE) {
            atomic_inc(&statsMouse);

            if (k_msgq_put(&radioMsgq, payload, K_NO_WAIT) != 0) {
                atomic_inc(&statsDropped);
            }
        } else if (length == RADIO_KEYBOARD_REPORT_SIZE) {
            atomic_inc(&statsKeyboard);

            if (k_msgq_put(&radioKeyboardMsgq, payload, K_NO_WAIT) != 0) {
                atomic_inc(&statsDropped);
            }
        } else if (length == RADIO_FRAME_SIZE) {
            atomic_inc(&statsFrame);

            if (k_msgq_put(&radioFrameMsgq, payload, K_NO_WAIT) != 0) {
                atomic_inc(&statsDropped);
            }
        } else {
            atomic_inc(&statsOther);
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
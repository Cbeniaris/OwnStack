#ifndef RADIO_H
#define RADIO_H

#include <stdint.h>

#include <zephyr/kernel.h>

// Size of one mouse report: [buttons][X low][X high][Y low][Y high][wheel]
#define RADIO_REPORT_SIZE 6

// Size of one keyboard report: [modifiers][reserved][key 1] ... [key 6]
#define RADIO_KEYBOARD_REPORT_SIZE 8

// Size of one config frame (host command protocol). A frame is always exactly this many bytes.
#define RADIO_FRAME_SIZE 32

/*
 * Initializes the Gazell link layer in host role (the dongle) and enables it.
 * Listens on pipe 0 with a fixed address that must match the mouse (see radio.c).
 * Returns 0 on success, -EIO if the glue code, the link layer or any setting failed.
 */
int radioInit(void);

/*
 * Takes one received mouse report from the receive queue.
 *   report  - output: receives RADIO_REPORT_SIZE bytes
 *   timeout - how long to wait for a report (K_NO_WAIT, K_MSEC(n), K_FOREVER)
 * Returns 0 if a report was copied into report, a negative errno (-ENOMSG or
 * -EAGAIN) if none arrived in time.
 */
int radioReceive(uint8_t *report, k_timeout_t timeout);

/*
 * Takes one received keyboard report from the keyboard queue.
 *   report  - output: receives RADIO_KEYBOARD_REPORT_SIZE bytes
 *   timeout - how long to wait for a report (K_NO_WAIT, K_MSEC(n), K_FOREVER)
 * Returns 0 if a report was copied into report, a negative errno (-ENOMSG or
 * -EAGAIN) if none arrived in time.
 */
int radioReceiveKeyboard(uint8_t *report, k_timeout_t timeout);

/*
 * Takes one received config frame (a response from the mouse) from the frame queue.
 *   frame   - output: receives RADIO_FRAME_SIZE bytes
 *   timeout - how long to wait for a frame (K_NO_WAIT, K_MSEC(n), K_FOREVER)
 * Returns 0 if a frame was copied into frame, a negative errno (-ENOMSG or
 * -EAGAIN) if none arrived in time.
 */
int radioReceiveFrame(uint8_t *frame, k_timeout_t timeout);

/*
 * Queues one config frame (a request from the PC) for the mouse. Gazell can only send to
 * the mouse inside the acknowledgment of a packet the mouse transmits, so the frame waits
 * until the mouse transmits next. It replaces a request that is still waiting, and it is
 * removed if the mouse has not picked it up after RADIO_REQUEST_HOLD_MS (see radio.c).
 *   frame - RADIO_FRAME_SIZE bytes (copied)
 * Returns 0 if the frame was queued, -ENODEV if the radio is not initialized yet,
 * -EIO if Gazell refused it.
 */
int radioSendRequest(const uint8_t *frame);

#endif /* RADIO_H */
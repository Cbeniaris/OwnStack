#include <stdio.h>
#include <stdint.h>

#include <zephyr/kernel.h>
#include <zephyr/device.h>

#include <zephyr/usb/usbd.h>
#include <zephyr/usb/class/usbd_hid.h>

#include "radio.h"

// ---- USB: CDC ACM console + HID mouse + HID config interface (own USB device context) ----

// Zephyr's test vendor ID, fine for development only. Replace before sharing the device.
// The product ID differs from the mouse (0x0100) so both can be plugged in at once.
#define DONGLE_USB_VID       0x2fe3
#define DONGLE_USB_PID       0x0101
// Max bus power, in units of 2 mA (125 = 250 mA)
#define DONGLE_USB_MAX_POWER 125

// Report = 1 byte buttons + 2 bytes X + 2 bytes Y + 1 byte wheel (same as the mouse sends)
#define MOUSE_REPORT_SIZE   6
#define MOUSE_AXIS_MAX      32767
#define MOUSE_WHEEL_MAX     127

/*
 * HID report descriptor: 5 buttons (left, right, middle, side 1, side 2),
 * 3 bits padding, 16-bit relative X, 16-bit relative Y, 8-bit relative wheel.
 * Report layout (little endian): [buttons][X low][X high][Y low][Y high][wheel]
 * Identical to the descriptor in the mouse firmware.
*/
static const uint8_t hidReportDesc[] = {
    0x05, 0x01,         // Usage Page (Generic Desktop)
    0x09, 0x02,         // Usage (Mouse)
    0xA1, 0x01,         // Collection (Application)
    0x09, 0x01,         //   Usage (Pointer)
    0xA1, 0x00,         //   Collection (Physical)
    0x05, 0x09,         //     Usage Page (Button)
    0x19, 0x01,         //     Usage Minimum (Button 1)
    0x29, 0x05,         //     Usage Maximum (Button 5)
    0x15, 0x00,         //     Logical Minimum (0)
    0x25, 0x01,         //     Logical Maximum (1)
    0x95, 0x05,         //     Report Count (5)
    0x75, 0x01,         //     Report Size (1)
    0x81, 0x02,         //     Input (Data, Variable, Absolute)
    0x95, 0x01,         //     Report Count (1)
    0x75, 0x03,         //     Report Size (3)
    0x81, 0x01,         //     Input (Constant) - padding
    0x05, 0x01,         //     Usage Page (Generic Desktop)
    0x09, 0x30,         //     Usage (X)
    0x09, 0x31,         //     Usage (Y)
    0x16, 0x01, 0x80,   //     Logical Minimum (-32767)
    0x26, 0xFF, 0x7F,   //     Logical Maximum (32767)
    0x75, 0x10,         //     Report Size (16)
    0x95, 0x02,         //     Report Count (2)
    0x81, 0x06,         //     Input (Data, Variable, Relative)
    0x09, 0x38,         //     Usage (Wheel)
    0x15, 0x81,         //     Logical Minimum (-127)
    0x25, 0x7F,         //     Logical Maximum (127)
    0x75, 0x08,         //     Report Size (8)
    0x95, 0x01,         //     Report Count (1)
    0x81, 0x06,         //     Input (Data, Variable, Relative)
    0xC0,               //   End Collection
    0xC0                // End Collection
};

// USB device context using the default USB device controller
USBD_DEVICE_DEFINE(dongleUsbd,
                   DEVICE_DT_GET(DT_NODELABEL(zephyr_udc0)),
                   DONGLE_USB_VID, DONGLE_USB_PID);

USBD_DESC_LANG_DEFINE(dongleLang);
USBD_DESC_MANUFACTURER_DEFINE(dongleMfr, "Beniaris");
USBD_DESC_PRODUCT_DEFINE(dongleProduct, "OwnStackReceiver");
USBD_DESC_CONFIG_DEFINE(dongleFsCfgDesc, "FS Configuration");

// attributes = 0: bus powered, no remote wakeup
USBD_CONFIGURATION_DEFINE(dongleFsConfig, 0, DONGLE_USB_MAX_POWER, &dongleFsCfgDesc);

// Classes that must not be registered automatically (DFU mode instance)
static const char *const dongleBlocklist[] = {
    "dfu_dfu",
    NULL,
};

static const struct device *hidDev;
static volatile bool hidReady = false;

// Enable the USB device when VBUS appears, disable it when VBUS is removed
static void usbdMsgCb(struct usbd_context *const ctx, const struct usbd_msg *const msg)
{
    if (usbd_can_detect_vbus(ctx)) {
        if (msg->type == USBD_MSG_VBUS_READY) {
            usbd_enable(ctx);
        }

        if (msg->type == USBD_MSG_VBUS_REMOVED) {
            usbd_disable(ctx);
        }
    }
}

// Called by the HID class when the host has configured (or dropped) the HID interface
static void hidIfaceReady(const struct device *dev, const bool ready)
{
    hidReady = ready;
}

static int hidGetReport(const struct device *dev, const uint8_t type, const uint8_t id,
    const uint16_t len, uint8_t *const buf)
{
    return 0;
}

static struct hid_device_ops hidOps = {
    .iface_ready = hidIfaceReady,
    .get_report = hidGetReport,
};

/*
 * HID report descriptor of the config interface (host command protocol): one vendor-defined
 * application collection with a 32-byte input report and a 32-byte output report, no report
 * IDs. Identical to the descriptor in the mouse firmware. The dongle only forwards the frames
 * between this interface and the radio, it does not look at their contents.
*/
static const uint8_t configReportDesc[] = {
    0x06, 0x00, 0xFF,   // Usage Page (Vendor Defined 0xFF00)
    0x09, 0x01,         // Usage (1)
    0xA1, 0x01,         // Collection (Application)
    0x09, 0x02,         //   Usage (2) - input data
    0x15, 0x00,         //   Logical Minimum (0)
    0x26, 0xFF, 0x00,   //   Logical Maximum (255)
    0x75, 0x08,         //   Report Size (8)
    0x95, RADIO_FRAME_SIZE, //   Report Count (32)
    0x81, 0x02,         //   Input (Data, Variable, Absolute)
    0x09, 0x03,         //   Usage (3) - output data
    0x91, 0x02,         //   Output (Data, Variable, Absolute)
    0xC0                // End Collection
};

static const struct device *configHidDev;
static volatile bool configReady = false;

// Buffer for response frames. Only the config thread sends, so one buffer is enough.
UDC_STATIC_BUF_DEFINE(configReportBuf, RADIO_FRAME_SIZE);

// Config thread: stack size, priority (a higher number is a lower priority) and submit attempts
#define FRAME_THREAD_STACK_SIZE 1536
#define FRAME_THREAD_PRIORITY   5
#define FRAME_SUBMIT_ATTEMPTS   10

// Keyboard thread: same settings. A keyboard report is the full key state, so a dropped report
// can leave a key stuck until the next one, which is why it gets more attempts than a config frame.
#define KEYBOARD_THREAD_STACK_SIZE 1024
#define KEYBOARD_THREAD_PRIORITY   5
#define KEYBOARD_SUBMIT_ATTEMPTS   50

// Called by the HID class when the host has configured (or dropped) the config interface
static void configIfaceReady(const struct device *dev, const bool ready)
{
    configReady = ready;
}

static int configGetReport(const struct device *dev, const uint8_t type, const uint8_t id,
    const uint16_t len, uint8_t *const buf)
{
    return 0;
}

/*
 * Called by the HID class when the host sends an output report: one request frame for the mouse.
 * It is handed to the radio, which sends it to the mouse the next time the mouse transmits.
 * A frame of the wrong size is dropped (the host retransmits after its timeout).
*/
static void configOutputReport(const struct device *dev, const uint16_t len, const uint8_t *const buf)
{
    if (len == RADIO_FRAME_SIZE) {
        radioSendRequest(buf);
    }
}

/*
 * The HID class requires a set_report callback whenever an interrupt OUT endpoint is declared
 * (out-report-size), otherwise hid_device_register fails. Request frames arrive through the
 * interrupt OUT endpoint (configOutputReport), so Set Report over the control pipe is rejected
 * and the protocol has exactly one way in.
*/
static int configSetReport(const struct device *dev, const uint8_t type, const uint8_t id,
    const uint16_t len, const uint8_t *const buf)
{
    return -ENOTSUP;
}

static struct hid_device_ops configOps = {
    .iface_ready = configIfaceReady,
    .get_report = configGetReport,
    .set_report = configSetReport,
    .output_report = configOutputReport,
};

/*
 * Forwards every response frame the mouse sends over the radio to the host as an input report of
 * the config interface. It runs in its own thread so the main loop only has to serve the mouse.
 * A frame is dropped if the host has not configured the interface or if the submit still fails
 * after FRAME_SUBMIT_ATTEMPTS tries 1 ms apart (the host retransmits after its timeout).
*/
static void configForwardThread(void *p1, void *p2, void *p3)
{
    ARG_UNUSED(p1);
    ARG_UNUSED(p2);
    ARG_UNUSED(p3);

    while (1) {
        if (radioReceiveFrame(configReportBuf, K_FOREVER) != 0) {
            continue;
        }

        if (!configReady) {
            continue;
        }

        for (int attempt = 0; attempt < FRAME_SUBMIT_ATTEMPTS; attempt++) {
            if (hid_device_submit_report(configHidDev, RADIO_FRAME_SIZE, configReportBuf) == 0) {
                break;
            }

            k_msleep(1);
        }
    }
}

K_THREAD_DEFINE(configForwardTid, FRAME_THREAD_STACK_SIZE, configForwardThread,
                NULL, NULL, NULL, FRAME_THREAD_PRIORITY, 0, 0);

/*
 * HID report descriptor of the keyboard interface: the standard keyboard layout without LEDs.
 * Report layout: [modifiers][reserved][key 1] ... [key 6], no report IDs.
 * Identical to the descriptor in the mouse firmware. The dongle only forwards the reports,
 * it does not look at which keys they hold.
*/
static const uint8_t keyboardReportDesc[] = {
    0x05, 0x01,         // Usage Page (Generic Desktop)
    0x09, 0x06,         // Usage (Keyboard)
    0xA1, 0x01,         // Collection (Application)
    0x05, 0x07,         //   Usage Page (Keyboard/Keypad)
    0x19, 0xE0,         //   Usage Minimum (Left Control)
    0x29, 0xE7,         //   Usage Maximum (Right GUI)
    0x15, 0x00,         //   Logical Minimum (0)
    0x25, 0x01,         //   Logical Maximum (1)
    0x75, 0x01,         //   Report Size (1)
    0x95, 0x08,         //   Report Count (8)
    0x81, 0x02,         //   Input (Data, Variable, Absolute) - modifier bits
    0x95, 0x01,         //   Report Count (1)
    0x75, 0x08,         //   Report Size (8)
    0x81, 0x01,         //   Input (Constant) - reserved byte
    0x95, 0x06,         //   Report Count (6)
    0x75, 0x08,         //   Report Size (8)
    0x15, 0x00,         //   Logical Minimum (0)
    0x26, 0xFF, 0x00,   //   Logical Maximum (255)
    0x05, 0x07,         //   Usage Page (Keyboard/Keypad)
    0x19, 0x00,         //   Usage Minimum (0)
    0x2A, 0xFF, 0x00,   //   Usage Maximum (255)
    0x81, 0x00,         //   Input (Data, Array) - six key codes
    0xC0                // End Collection
};

static const struct device *keyboardHidDev;
static volatile bool keyboardReady = false;

// Buffer for keyboard reports. Only the keyboard thread sends, so one buffer is enough.
UDC_STATIC_BUF_DEFINE(keyboardReportBuf, RADIO_KEYBOARD_REPORT_SIZE);

// Called by the HID class when the host has configured (or dropped) the keyboard interface
static void keyboardIfaceReady(const struct device *dev, const bool ready)
{
    keyboardReady = ready;
}

static int keyboardGetReport(const struct device *dev, const uint8_t type, const uint8_t id,
    const uint16_t len, uint8_t *const buf)
{
    return 0;
}

static struct hid_device_ops keyboardOps = {
    .iface_ready = keyboardIfaceReady,
    .get_report = keyboardGetReport,
};

/*
 * Forwards every keyboard report the mouse sends over the radio to the host as an input report
 * of the keyboard interface. It runs in its own thread so the main loop only has to serve the
 * mouse. A report is dropped if the host has not configured the interface or if the submit still
 * fails after KEYBOARD_SUBMIT_ATTEMPTS tries 1 ms apart.
*/
static void keyboardForwardThread(void *p1, void *p2, void *p3)
{
    ARG_UNUSED(p1);
    ARG_UNUSED(p2);
    ARG_UNUSED(p3);

    while (1) {
        if (radioReceiveKeyboard(keyboardReportBuf, K_FOREVER) != 0) {
            continue;
        }

        if (!keyboardReady) {
            continue;
        }

        for (int attempt = 0; attempt < KEYBOARD_SUBMIT_ATTEMPTS; attempt++) {
            if (hid_device_submit_report(keyboardHidDev, RADIO_KEYBOARD_REPORT_SIZE, keyboardReportBuf) == 0) {
                break;
            }

            k_msleep(1);
        }
    }
}

K_THREAD_DEFINE(keyboardForwardTid, KEYBOARD_THREAD_STACK_SIZE, keyboardForwardThread,
                NULL, NULL, NULL, KEYBOARD_THREAD_PRIORITY, 0, 0);

/*
 * Registers the HID mouse, the HID config interface and the HID keyboard, builds the USB device
 * (CDC ACM console + HID) and starts it.
 * Returns 0 on success, negative errno on failure.
*/
static int usbHidInit(void)
{
    int err;

    hidDev = DEVICE_DT_GET(DT_NODELABEL(hid_dev_0));
    if (!device_is_ready(hidDev)) {
        return -EIO;
    }

    err = hid_device_register(hidDev, hidReportDesc, sizeof(hidReportDesc), &hidOps);
    if (err != 0) {
        return err;
    }

    configHidDev = DEVICE_DT_GET(DT_NODELABEL(hid_dev_1));
    if (!device_is_ready(configHidDev)) {
        return -EIO;
    }

    err = hid_device_register(configHidDev, configReportDesc, sizeof(configReportDesc), &configOps);
    if (err != 0) {
        return err;
    }

    keyboardHidDev = DEVICE_DT_GET(DT_NODELABEL(hid_dev_2));
    if (!device_is_ready(keyboardHidDev)) {
        return -EIO;
    }

    err = hid_device_register(keyboardHidDev, keyboardReportDesc, sizeof(keyboardReportDesc), &keyboardOps);
    if (err != 0) {
        return err;
    }

    err = usbd_add_descriptor(&dongleUsbd, &dongleLang);
    if (err != 0) {
        return err;
    }

    err = usbd_add_descriptor(&dongleUsbd, &dongleMfr);
    if (err != 0) {
        return err;
    }

    err = usbd_add_descriptor(&dongleUsbd, &dongleProduct);
    if (err != 0) {
        return err;
    }

    err = usbd_add_configuration(&dongleUsbd, USBD_SPEED_FS, &dongleFsConfig);
    if (err != 0) {
        return err;
    }

    err = usbd_register_all_classes(&dongleUsbd, USBD_SPEED_FS, 1, dongleBlocklist);
    if (err != 0) {
        return err;
    }

    // CDC ACM has multiple interfaces, so use the Interface Association Descriptor class triple
    usbd_device_set_code_triple(&dongleUsbd, USBD_SPEED_FS, USB_BCC_MISCELLANEOUS, 0x02, 0x01);

    err = usbd_msg_register_cb(&dongleUsbd, usbdMsgCb);
    if (err != 0) {
        return err;
    }

    err = usbd_init(&dongleUsbd);
    if (err != 0) {
        return err;
    }

    // Without VBUS detection there is no VBUS_READY message, so enable right away
    if (!usbd_can_detect_vbus(&dongleUsbd)) {
        err = usbd_enable(&dongleUsbd);
        if (err != 0) {
            return err;
        }
    }

    return 0;
}

int main(void)
{
    // Bring up USB first (CDC ACM console + HID mouse) so printf output is available early
    int usbRet = usbHidInit();
    if (usbRet != 0) {
        return usbRet;
    }

    // Startup delay so the console is up before the first print
    k_msleep(1000);

    int radioRet = radioInit();
    printf("Radio init returned: %d\n", radioRet);
    if (radioRet != 0) {
        return radioRet;
    }

    // --------- MAIN RECEIVE LOOP ---------
    UDC_STATIC_BUF_DEFINE(report, MOUSE_REPORT_SIZE);
    uint8_t packet[RADIO_REPORT_SIZE];

    // motion and wheel add up between USB reports, buttons are the latest state received
    int32_t pendingDx = 0;
    int32_t pendingDy = 0;
    int32_t pendingWheel = 0;
    uint8_t buttons = 0;
    uint8_t lastSentButtons = 0;

    while (1) {
        bool havePending = (pendingDx != 0 || pendingDy != 0 || pendingWheel != 0 ||
                            buttons != lastSentButtons);

        // wait for a report, or retry the USB submit after 1 ms if something is still queued
        int getRet = radioReceive(packet, havePending ? K_MSEC(1) : K_FOREVER);

        // take everything that has arrived
        while (getRet == 0) {
            buttons = packet[0];
            pendingDx += (int16_t)(uint16_t)(packet[1] | (packet[2] << 8));
            pendingDy += (int16_t)(uint16_t)(packet[3] | (packet[4] << 8));
            pendingWheel += (int8_t)packet[5];

            getRet = radioReceive(packet, K_NO_WAIT);
        }

        if (!hidReady) {
            // host is not listening, drop everything instead of sending a huge jump later
            pendingDx = 0;
            pendingDy = 0;
            pendingWheel = 0;
            lastSentButtons = buttons;
        } else if (pendingDx != 0 || pendingDy != 0 || pendingWheel != 0 ||
                   buttons != lastSentButtons) {
            int32_t sendDx = pendingDx;
            int32_t sendDy = pendingDy;
            int32_t sendWheel = pendingWheel;

            if (sendDx > MOUSE_AXIS_MAX) {
                sendDx = MOUSE_AXIS_MAX;
            } else if (sendDx < -MOUSE_AXIS_MAX) {
                sendDx = -MOUSE_AXIS_MAX;
            }

            if (sendDy > MOUSE_AXIS_MAX) {
                sendDy = MOUSE_AXIS_MAX;
            } else if (sendDy < -MOUSE_AXIS_MAX) {
                sendDy = -MOUSE_AXIS_MAX;
            }

            if (sendWheel > MOUSE_WHEEL_MAX) {
                sendWheel = MOUSE_WHEEL_MAX;
            } else if (sendWheel < -MOUSE_WHEEL_MAX) {
                sendWheel = -MOUSE_WHEEL_MAX;
            }

            uint16_t ux = (uint16_t)sendDx;
            uint16_t uy = (uint16_t)sendDy;

            report[0] = buttons;
            report[1] = ux & 0xFF;      // X low
            report[2] = ux >> 8;        // X high
            report[3] = uy & 0xFF;      // Y low
            report[4] = uy >> 8;        // Y high
            report[5] = (uint8_t)(int8_t)sendWheel;

            // keep everything queued if the report could not be submitted
            if (hid_device_submit_report(hidDev, MOUSE_REPORT_SIZE, report) == 0) {
                pendingDx -= sendDx;
                pendingDy -= sendDy;
                pendingWheel -= sendWheel;
                lastSentButtons = buttons;
            }
        }
    }

    return 0;
}
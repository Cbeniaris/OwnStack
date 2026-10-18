# OwnStack Dongle and Config App

The open source host side of a DIY wireless gaming mouse. This repo contains:

- **Dongle firmware** for the Nordic nRF52840 Dongle (PCA10059)
- **Config app**, a JavaFX desktop application for editing the mouse's profiles and macros

The mouse firmware is not part of this repo, so the dongle and app are not usable on their own.

This project is a work in progress.

## Dongle firmware

The dongle receives the mouse's reports over Gazell and presents them to the PC as a USB HID mouse. It also exposes a separate config HID interface (vendor usage page `0xFF00`, 32-byte input and output reports, USB PID `0x0101`) that passes config frames between the PC and the mouse over the radio link. The dongle only forwards frames. It does not generate or interpret them.

### Building

The firmware is built with the nRF Connect SDK 3.4.1 using the nRF Connect extension for VS Code.

1. Open the dongle project in VS Code with the nRF Connect extension.
2. Create a build configuration for the nRF52840 Dongle (PCA10059).
3. Build and flash the dongle.

## Config app

The app is written in Java with JavaFX 17.0.12 and uses [hid4java](https://github.com/gary-rowe/hid4java) to talk to the mouse over HID. It connects either directly to the mouse over its USB cable (USB PID `0x0100`) or through the dongle. It has been developed and tested on Linux (Fedora 43).

- **Profiles tab:** switch between profiles, rename them, and edit CPI and button bindings. Keys are picked from drop-down menus, never typed in.
- **Macros tab:** edit the macro library, including each macro's trigger mode and its step list.

Edits are only sent to the mouse when you press **Apply**. **Discard** reverts to what was last read from the mouse.

### Running

You need a JDK and Maven. From the app's project directory:

```
mvn -q compile exec:java -Dexec.mainClass=OwnStackApp
```

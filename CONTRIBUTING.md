# Contributing

Thanks for wanting to help.

## Camera reports

The app is tested on one camera (USB ID `0bda:5840`). If you own another Tiny1-C based camera, such as an InfiRay P2 Pro or Topdon TC001 (`0bda:5830`), a report helps a lot, good or bad. Please include:
- the camera model and its USB ID
- the phone model and Android version
- what works, what doesn't, and `adb logcat` output if something fails

## Bug reports

Open an issue with the phone, Android version, Emberglass version and steps to reproduce.

## Code

- Plain Java, OpenGL ES and a small C part, no Gradle: build with `./build.sh` and run `test/run.sh` (see the README).
- Keep changes small and focused, one topic per pull request.
- Never send flash-write, firmware-update or reset commands to the camera.

For bigger changes, open an issue or a discussion first so we can agree on the approach.

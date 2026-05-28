BLE Helper Tool

## Transport Integration

- The app now provides a unified launcher flow that lets users choose BLE or classic Bluetooth SPP.
- BLE entry: `com.bhm.demo.ui.MainActivity`
- SPP entry: `de.kai_morich.simple_bluetooth_terminal.MainActivity`

## Kotlin Migration Status

- SPP module classes have been fully migrated to Kotlin (`.java` removed under `spp/src/main/java/de/kai_morich/simple_bluetooth_terminal`).
- Existing behavior is preserved: serial connect/disconnect, terminal send/receive, private protocol parsing, Tap parameter dialog, log save/share.

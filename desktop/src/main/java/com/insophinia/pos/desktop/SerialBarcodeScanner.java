package com.insophinia.pos.desktop;

import com.fazecast.jSerialComm.SerialPort;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Optional serial barcode scanner bridge. Keyboard-wedge scanners need no configuration. */
public final class SerialBarcodeScanner implements AutoCloseable {
    private final SerialPort port;
    private final StringBuilder buffer = new StringBuilder();
    private final Consumer<String> onBarcode;

    public SerialBarcodeScanner(String portName, int baudRate, Consumer<String> onBarcode) {
        this.onBarcode = onBarcode;
        this.port = SerialPort.getCommPort(portName);
        port.setBaudRate(baudRate); port.setNumDataBits(8); port.setNumStopBits(SerialPort.ONE_STOP_BIT); port.setParity(SerialPort.NO_PARITY);
        if (!port.openPort()) throw new IllegalStateException("Unable to open scanner port: " + portName);
        port.addDataListener(new com.fazecast.jSerialComm.SerialPortDataListener() {
            @Override public int getListeningEvents() { return SerialPort.LISTENING_EVENT_DATA_AVAILABLE; }
            @Override public void serialEvent(com.fazecast.jSerialComm.SerialPortEvent event) {
                if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;
                byte[] bytes = new byte[port.bytesAvailable()];
                if (bytes.length == 0) return;
                int read = port.readBytes(bytes, bytes.length);
                if (read <= 0) return;
                synchronized (buffer) {
                    String chunk = new String(bytes, 0, read, StandardCharsets.UTF_8);
                    for (int i = 0; i < chunk.length(); i++) {
                        char ch = chunk.charAt(i);
                        if (ch == '\r' || ch == '\n') {
                            String code = buffer.toString().trim(); buffer.setLength(0);
                            if (!code.isEmpty()) onBarcode.accept(code);
                        } else if (!Character.isISOControl(ch)) buffer.append(ch);
                    }
                }
            }
        });
    }
    public static String[] availablePorts() { return java.util.Arrays.stream(SerialPort.getCommPorts()).map(SerialPort::getSystemPortName).toArray(String[]::new); }
    @Override public void close() { port.removeDataListener(); port.closePort(); }
}

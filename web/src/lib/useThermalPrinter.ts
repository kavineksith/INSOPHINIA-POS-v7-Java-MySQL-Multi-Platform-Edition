
import { useState, useCallback, useRef, useEffect } from 'react';

interface ThermalPrinterState {
    isConnected: boolean;
    isSupported: boolean;
    isPrinting: boolean;
    error: string | null;
    portName: string | null;
}

// ESC/POS command constants
const ESC = 0x1B;
const GS = 0x1D;
const LF = 0x0A;

const ESC_POS = {
    INIT: new Uint8Array([ESC, 0x40]),                    // Initialize printer
    ALIGN_CENTER: new Uint8Array([ESC, 0x61, 0x01]),      // Center alignment
    ALIGN_LEFT: new Uint8Array([ESC, 0x61, 0x00]),        // Left alignment
    ALIGN_RIGHT: new Uint8Array([ESC, 0x61, 0x02]),       // Right alignment
    BOLD_ON: new Uint8Array([ESC, 0x45, 0x01]),           // Bold on
    BOLD_OFF: new Uint8Array([ESC, 0x45, 0x00]),          // Bold off
    DOUBLE_HEIGHT: new Uint8Array([ESC, 0x21, 0x10]),     // Double height
    NORMAL_SIZE: new Uint8Array([ESC, 0x21, 0x00]),       // Normal text size
    UNDERLINE_ON: new Uint8Array([ESC, 0x2D, 0x01]),      // Underline on
    UNDERLINE_OFF: new Uint8Array([ESC, 0x2D, 0x00]),     // Underline off
    CUT_PAPER: new Uint8Array([GS, 0x56, 0x00]),          // Full cut
    PARTIAL_CUT: new Uint8Array([GS, 0x56, 0x01]),        // Partial cut
    FEED_LINES: (n: number) => new Uint8Array([ESC, 0x64, n]), // Feed n lines
    LINE_SPACING: (n: number) => new Uint8Array([ESC, 0x33, n]), // Set line spacing
    OPEN_DRAWER: new Uint8Array([ESC, 0x70, 0x00, 0x19, 0xFA]), // Open cash drawer
};

// Encode text to Uint8Array
function textToBytes(text: string): Uint8Array {
    return new TextEncoder().encode(text + '\n');
}

function dashLine(width: number = 32): Uint8Array {
    return textToBytes('-'.repeat(width));
}

function formatLine(left: string, right: string, width: number = 32): string {
    const space = width - left.length - right.length;
    return left + ' '.repeat(Math.max(1, space)) + right;
}

export interface ReceiptData {
    shopName: string;
    shopAddress: string;
    shopPhone: string;
    billNumber: string;
    date: string;
    time: string;
    cashier: string;
    items: Array<{
        name: string;
        qty: string;
        price: string;
        total: string;
    }>;
    subtotal: string;
    vatAmount?: string;
    ssclAmount?: string;
    taxTotal?: string;
    discount: string;
    total: string;
    paid: string;
    change: string;
    pointsEarned?: number;
    pointsRedeemed?: number;
}

/**
 * Hook for direct thermal printing via Web Serial API (ESC/POS).
 * Supports USB and Bluetooth-paired thermal printers in Chrome/Edge.
 * Falls back to window.print() on unsupported browsers.
 */
export function useThermalPrinter() {
    const [state, setState] = useState<ThermalPrinterState>({
        isConnected: false,
        isSupported: false,
        isPrinting: false,
        error: null,
        portName: null,
    });

    const portRef = useRef<any>(null);
    const writerRef = useRef<WritableStreamDefaultWriter | null>(null);

    // Check Web Serial API support
    useEffect(() => {
        const supported = typeof navigator !== 'undefined' && 'serial' in navigator;
        setState(prev => ({ ...prev, isSupported: supported }));
    }, []);

    // Connect to thermal printer
    const connect = useCallback(async (): Promise<boolean> => {
        if (!('serial' in navigator)) {
            setState(prev => ({ ...prev, error: 'Web Serial API not supported in this browser. Use Chrome or Edge.' }));
            return false;
        }

        try {
            const port = await (navigator as any).serial.requestPort();
            await port.open({ baudRate: 9600 }); // Most thermal printers use 9600

            portRef.current = port;
            const writer = port.writable?.getWriter();
            writerRef.current = writer || null;

            const info = port.getInfo();
            setState(prev => ({
                ...prev,
                isConnected: true,
                error: null,
                portName: info?.usbProductId ? `USB Printer (${info.usbProductId})` : 'Serial Printer',
            }));

            // Listen for disconnect
            port.addEventListener('disconnect', () => {
                setState(prev => ({ ...prev, isConnected: false, portName: null }));
                portRef.current = null;
                writerRef.current = null;
            });

            return true;
        } catch (err: any) {
            if (err.name !== 'NotFoundError') { // User cancelled picker
                setState(prev => ({ ...prev, error: err.message || 'Failed to connect' }));
            }
            return false;
        }
    }, []);

    // Disconnect
    const disconnect = useCallback(async () => {
        try {
            if (writerRef.current) {
                writerRef.current.releaseLock();
                writerRef.current = null;
            }
            if (portRef.current) {
                await portRef.current.close();
                portRef.current = null;
            }
        } catch { }
        setState(prev => ({ ...prev, isConnected: false, portName: null }));
    }, []);

    // Write raw bytes to printer
    const writeRaw = useCallback(async (data: Uint8Array) => {
        if (!writerRef.current) throw new Error('Printer not connected');
        await writerRef.current.write(data);
    }, []);

    // Print a receipt using ESC/POS commands
    const printReceipt = useCallback(async (receipt: ReceiptData): Promise<boolean> => {
        if (!writerRef.current) {
            setState(prev => ({ ...prev, error: 'Printer not connected' }));
            return false;
        }

        setState(prev => ({ ...prev, isPrinting: true, error: null }));

        try {
            const w = writeRaw;
            const W = 32; // Character width for 58mm paper (48 for 80mm)

            // Initialize
            await w(ESC_POS.INIT);
            await w(ESC_POS.LINE_SPACING(60));

            // Header
            await w(ESC_POS.ALIGN_CENTER);
            await w(ESC_POS.BOLD_ON);
            await w(ESC_POS.DOUBLE_HEIGHT);
            await w(textToBytes(receipt.shopName));
            await w(ESC_POS.NORMAL_SIZE);
            await w(ESC_POS.BOLD_OFF);
            await w(textToBytes(receipt.shopAddress));
            await w(textToBytes(`Tel: ${receipt.shopPhone}`));

            await w(dashLine(W));

            // Bill info
            await w(ESC_POS.ALIGN_LEFT);
            await w(ESC_POS.BOLD_ON);
            await w(textToBytes(formatLine('INVOICE', `#${receipt.billNumber}`, W)));
            await w(ESC_POS.BOLD_OFF);
            await w(dashLine(W));
            await w(textToBytes(formatLine(`Date: ${receipt.date}`, receipt.time, W)));
            await w(textToBytes(`Cashier: ${receipt.cashier}`));
            await w(dashLine(W));

            // Items
            for (const item of receipt.items) {
                await w(textToBytes(item.name.toUpperCase().substring(0, W)));
                await w(textToBytes(formatLine(`  ${item.qty} x ${item.price}`, item.total, W)));
            }

            await w(dashLine(W));

            // Totals
            await w(textToBytes(formatLine('Subtotal', receipt.subtotal, W)));
            if (receipt.discount && receipt.discount !== '0.00') {
                await w(textToBytes(formatLine('Discount', `-${receipt.discount}`, W)));
            }
            if (receipt.vatAmount && receipt.vatAmount !== '0.00') {
                await w(textToBytes(formatLine('VAT (18%)', receipt.vatAmount, W)));
            }
            if (receipt.ssclAmount && receipt.ssclAmount !== '0.00') {
                await w(textToBytes(formatLine('SSCL (2.5%)', receipt.ssclAmount, W)));
            }

            await w(ESC_POS.BOLD_ON);
            await w(ESC_POS.DOUBLE_HEIGHT);
            await w(textToBytes(formatLine('TOTAL', receipt.total, W)));
            await w(ESC_POS.NORMAL_SIZE);
            await w(ESC_POS.BOLD_OFF);

            await w(dashLine(W));
            await w(textToBytes(formatLine('Paid', receipt.paid, W)));
            await w(textToBytes(formatLine('Change', receipt.change, W)));

            if (receipt.pointsEarned) {
                await w(dashLine(W));
                await w(textToBytes(formatLine('Points Earned', `+${receipt.pointsEarned}`, W)));
            }
            if (receipt.pointsRedeemed) {
                await w(textToBytes(formatLine('Points Used', `-${receipt.pointsRedeemed}`, W)));
            }

            // Footer
            await w(dashLine(W));
            await w(ESC_POS.ALIGN_CENTER);
            await w(ESC_POS.BOLD_ON);
            await w(textToBytes('THANK YOU!'));
            await w(ESC_POS.BOLD_OFF);
            await w(textToBytes('Visit us again soon'));
            await w(textToBytes(''));
            await w(textToBytes('Powered by INSOPHINIA POS'));

            // Feed and cut
            await w(ESC_POS.FEED_LINES(4));
            await w(ESC_POS.PARTIAL_CUT);

            setState(prev => ({ ...prev, isPrinting: false }));
            return true;
        } catch (err: any) {
            setState(prev => ({ ...prev, isPrinting: false, error: err.message }));
            return false;
        }
    }, [writeRaw]);

    // Print test receipt
    const printTest = useCallback(async (): Promise<boolean> => {
        return printReceipt({
            shopName: 'INSOPHINIA POS',
            shopAddress: 'Test Print',
            shopPhone: '+94 123 456 789',
            billNumber: 'TEST-001',
            date: new Date().toLocaleDateString(),
            time: new Date().toLocaleTimeString(),
            cashier: 'System',
            items: [
                { name: 'Test Item 1', qty: '1', price: '100.00', total: '100.00' },
                { name: 'Test Item 2', qty: '2', price: '50.00', total: '100.00' },
            ],
            subtotal: '200.00',
            discount: '0.00',
            total: '200.00',
            paid: '200.00',
            change: '0.00',
        });
    }, [printReceipt]);

    // Open cash drawer
    const openDrawer = useCallback(async (): Promise<boolean> => {
        if (!writerRef.current) return false;
        try {
            await writeRaw(ESC_POS.OPEN_DRAWER);
            return true;
        } catch { return false; }
    }, [writeRaw]);

    // Fallback: print using browser print dialog
    const printViaDialog = useCallback((url: string) => {
        const printWindow = window.open(url, '_blank');
        if (printWindow) {
            printWindow.onload = () => printWindow.print();
        }
    }, []);

    // Cleanup on unmount
    useEffect(() => {
        return () => { disconnect(); };
    }, [disconnect]);

    return {
        ...state,
        connect,
        disconnect,
        printReceipt,
        printTest,
        openDrawer,
        printViaDialog,
    };
}

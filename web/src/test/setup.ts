// Vitest global setup — runs once per test file before any test executes.
// Adds jest-dom matchers (toBeInTheDocument, toHaveValue, etc.) and a clean
// jsdom environment. sessionStorage is reset between tests by Vitest's default
// DOM isolation.
import '@testing-library/jest-dom';

// jsdom doesn't implement matchMedia (used by some UI libs). Stub it so tests
// that mount components querying prefers-color-scheme don't crash.
if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

// jsdom lacks ResizeObserver — recharts ResponsiveContainer throws without it.
// Stub a no-op implementation that calls back once with a 0×0 size.
class ResizeObserverStub {
  private cb: ResizeObserverCallback;
  constructor(cb: ResizeObserverCallback) { this.cb = cb; }
  observe(target: Element) {
    // Fire the callback once synchronously so the chart renders at zero size.
    this.cb([{ target, contentRect: { width: 0, height: 0, x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, toJSON: () => ({}) } } as any], this);
  }
  unobserve() {}
  disconnect() {}
}
(globalThis as any).ResizeObserver = (globalThis as any).ResizeObserver || ResizeObserverStub;

// html5-qrcode references HTMLCanvasElement.prototype — jsdom lacks canvas.
// Stub the constructor so Pos.tsx imports don't crash during unit tests.
if (typeof HTMLCanvasElement !== 'undefined') {
  HTMLCanvasElement.prototype.getContext = HTMLCanvasElement.prototype.getContext || (() => null);
}


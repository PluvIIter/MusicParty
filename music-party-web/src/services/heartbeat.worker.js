/**
 * Web Worker for high-precision heartbeats on mobile.
 * Mobile browsers throttle setInterval/setTimeout in background tabs,
 * but Workers are sometimes treated with higher priority or at least 
 * provide a separate timing context.
 */

const timers = new Map();

self.onmessage = (e) => {
    const { type, id, interval } = e.data;

    if (type === 'setInterval') {
        const timerId = setInterval(() => {
            self.postMessage({ type: 'tick', id });
        }, interval);
        timers.set(id, timerId);
    } else if (type === 'clearInterval') {
        const timerId = timers.get(id);
        if (timerId) {
            clearInterval(timerId);
            timers.delete(id);
        }
    } else if (type === 'setTimeout') {
        const timerId = setTimeout(() => {
            self.postMessage({ type: 'timeout', id });
            timers.delete(id);
        }, interval);
        timers.set(id, timerId);
    } else if (type === 'clearTimeout') {
        const timerId = timers.get(id);
        if (timerId) {
            clearTimeout(timerId);
            timers.delete(id);
        }
    }
};

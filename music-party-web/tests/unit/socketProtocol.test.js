import { beforeEach, describe, expect, it, vi } from 'vitest';
import { socketService } from '../../src/services/socket';
import { WS_DEST } from '../../src/constants/api';
const mocks = vi.hoisted(() => ({ config: null, handlers: new Map(), publish: vi.fn() }));
vi.mock('@stomp/stompjs', () => ({
    TickerStrategy: { Interval: 'interval', Worker: 'worker' },
    Client: class {
        constructor(config) {
            mocks.config = config;
        }
        activate() {
            this.active = true;
            mocks.config.onConnect({});
        }
        subscribe(topic, callback) {
            mocks.handlers.set(topic, callback);
            return { unsubscribe: vi.fn() };
        }
        publish(frame) {
            mocks.publish(frame);
        }
        deactivate() {
            this.active = false;
        }
    },
}));
beforeEach(() => {
    socketService.disconnect();
    mocks.handlers.clear();
    mocks.publish.mockClear();
});
describe('current compact-only socket protocol', () => {
    it('subscribes only current compact state channels and never requests old state', () => {
        socketService.connect({}, { onState: vi.fn() }, {});
        expect([...mocks.handlers.keys()].sort()).toEqual(
            [WS_DEST.APP_COMPACT, WS_DEST.TOPIC_COMPACT, WS_DEST.USER_COMPACT].sort()
        );
        socketService.send(WS_DEST.COMPACT_RESYNC);
        expect(mocks.publish).toHaveBeenCalledWith({ destination: '/app/player/compact-resync', body: '{}' });
    });
    it('applies full snapshots and recovers missing deltas with a current snapshot request', () => {
        const onState = vi.fn();
        socketService.connect({}, { onState }, {});
        const deliver = (frame) => mocks.handlers.get(WS_DEST.TOPIC_COMPACT)({ body: JSON.stringify(frame) });
        deliver({
            v: 1,
            epoch: 'current',
            seq: 1,
            base: 1,
            full: true,
            state: { nowPlaying: null, queue: [], isPaused: false },
        });
        deliver({ v: 1, epoch: 'current', seq: 3, base: 2, full: false, state: { isPaused: true } });
        expect(onState).toHaveBeenCalledTimes(1);
        expect(mocks.publish).toHaveBeenCalledWith({ destination: '/app/player/compact-resync', body: '{}' });
    });
});

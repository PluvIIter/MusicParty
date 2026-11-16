import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { toAdminConfigPayload, ADMIN_CONFIG_PAYLOAD_FIELDS } from '../../src/domain/adminConfig.js';

describe('current admin config schema', () => {
    it('sends canonical state field names unchanged', () => {
        expect(toAdminConfigPayload({ maxQueueSize: 500, maxHistorySize: 20 })).toEqual({
            maxQueueSize: 500,
            maxHistorySize: 20,
        });
    });
    it('sends generic source settings and preserves false and zero values', () => {
        expect(
            toAdminConfigPayload({ sources: { future: { enabled: false } }, maxUserSongs: 0, retainPlaylist: false })
        ).toEqual({ sources: { future: { enabled: false } }, maxUserSongs: 0, retainPlaylist: false });
    });
    it('drops unrelated state and undefined fields without mapping aliases', () => {
        expect(
            toAdminConfigPayload({ maxSize: 10, historySize: 2, privateDj: { mode: 'OFF' }, maxQueueSize: undefined })
        ).toEqual({});
    });
    it('matches the current backend request fields', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), '../src/main/java/org/thornex/musicparty/dto/AdminConfigUpdateRequest.java'),
            'utf8'
        );
        const body = source.slice(source.indexOf('(') + 1, source.indexOf(')'));
        const fields = [
            ...body.matchAll(
                /\b(?:Integer|Long|Boolean|Double|String|java\.util\.Map<String, SourceSettings>)\s+(\w+)/g
            ),
        ].map((m) => m[1]);
        expect(fields.length).toBeGreaterThan(5);
        expect([...ADMIN_CONFIG_PAYLOAD_FIELDS].sort()).toEqual(fields.sort());
    });
});

import { describe, it, expect } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { toAdminConfigPayload, ADMIN_CONFIG_PAYLOAD_FIELDS } from '../../src/domain/adminConfig.js';

describe('toAdminConfigPayload 字段重命名', () => {
    it('maxQueueSize 映射为 maxSize', () => {
        expect(toAdminConfigPayload({ maxQueueSize: 500 })).toEqual({ maxSize: 500 });
    });

    it('maxHistorySize 映射为 historySize', () => {
        expect(toAdminConfigPayload({ maxHistorySize: 20 })).toEqual({ historySize: 20 });
    });

    it('两个字段同时出现时都映射', () => {
        expect(toAdminConfigPayload({ maxQueueSize: 500, maxHistorySize: 20 })).toEqual({
            maxSize: 500,
            historySize: 20,
        });
    });

    it('同名字段原样透传', () => {
        expect(toAdminConfigPayload({ maxUserSongs: 100, voteSkipThreshold: 0.5, neteaseEnabled: true })).toEqual({
            maxUserSongs: 100,
            voteSkipThreshold: 0.5,
            neteaseEnabled: true,
        });
    });

    it('丢弃不属于 DTO 的字段', () => {
        const out = toAdminConfigPayload({
            maxQueueSize: 500,
            privateDj: { mode: 'OFF' },
            neteaseCookieConfigured: true,
        });
        expect(out).toEqual({ maxSize: 500 });
        expect(out.privateDj).toBeUndefined();
        expect(out.neteaseCookieConfigured).toBeUndefined();
    });

    it('不把旧字段名一起发出去', () => {
        const out = toAdminConfigPayload({ maxQueueSize: 500, maxHistorySize: 20 });
        expect(out.maxQueueSize).toBeUndefined();
        expect(out.maxHistorySize).toBeUndefined();
    });

    it('保留 false 与 0 这类假值', () => {
        expect(toAdminConfigPayload({ neteaseEnabled: false, maxUserSongs: 0 })).toEqual({
            neteaseEnabled: false,
            maxUserSongs: 0,
        });
    });

    it('undefined 字段不下发', () => {
        expect(toAdminConfigPayload({ maxQueueSize: undefined, maxUserSongs: 7 })).toEqual({ maxUserSongs: 7 });
    });

    it('空对象返回空对象', () => {
        expect(toAdminConfigPayload({})).toEqual({});
    });

    // 这条用例原本是把同一份字段表在测试里再抄一遍，后端加字段时它不会失败——
    // 名字叫「与后端一致」却防不住漂移。改成直接读后端的 DTO 源文件比对。
    it('白名单与后端 AdminConfigUpdateRequest 的字段集合一致', () => {
        const dtoPath = path.resolve(
            process.cwd(),
            '../src/main/java/org/thornex/musicparty/dto/AdminConfigUpdateRequest.java'
        );
        expect(existsSync(dtoPath), `后端 DTO 不存在：${dtoPath}`).toBe(true);

        const source = readFileSync(dtoPath, 'utf8');
        const body = source.slice(source.indexOf('(') + 1, source.lastIndexOf(')'));
        const fields = [...body.matchAll(/\b(?:Integer|Long|Boolean|Double|String)\s+(\w+)/g)].map((m) => m[1]);

        // 前置：正则确实抓到了字段，否则下面的比较会变成「空数组 vs 空数组」的自我满足
        expect(fields.length).toBeGreaterThan(5);
        expect([...ADMIN_CONFIG_PAYLOAD_FIELDS].sort()).toEqual(fields.sort());
    });
});

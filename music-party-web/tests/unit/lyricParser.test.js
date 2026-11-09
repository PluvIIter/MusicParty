import { describe, it, expect } from 'vitest';
import { parseLyrics } from '../../src/domain/lyricParser.js';

describe('parseLyrics', () => {
    it('空输入返回空数组', () => {
        expect(parseLyrics(null)).toEqual([]);
        expect(parseLyrics(undefined)).toEqual([]);
        expect(parseLyrics('')).toEqual([]);
    });

    it('解析分:秒.毫秒为毫秒数', () => {
        expect(parseLyrics('[00:05.500]hello')).toEqual([{ time: 5500, text: 'hello' }]);
    });

    it('毫秒为 2 位时右补零', () => {
        expect(parseLyrics('[01:02.50]hi')).toEqual([{ time: 62500, text: 'hi' }]);
    });

    it('无毫秒段时毫秒为 0', () => {
        expect(parseLyrics('[01:02]hi')).toEqual([{ time: 62000, text: 'hi' }]);
    });

    it('分钟数可超过两位', () => {
        expect(parseLyrics('[100:00.000]long')).toEqual([{ time: 6000000, text: 'long' }]);
    });

    it('丢弃时间戳后无文本的行', () => {
        expect(parseLyrics('[00:01.000]\n[00:02.000]real')).toEqual([{ time: 2000, text: 'real' }]);
    });

    it('一行内多个时间戳时只取第一个，且只去掉第一个', () => {
        expect(parseLyrics('[00:01.000][00:09.000]dup')).toEqual([{ time: 1000, text: '[00:09.000]dup' }]);
    });

    it('不排序，保持输入顺序', () => {
        const lrc = '[00:20.000]b\n[00:10.000]a';
        expect(parseLyrics(lrc).map((l) => l.text)).toEqual(['b', 'a']);
    });

    it('不处理 offset 标签（它不是时间戳，整行按普通文本处理）', () => {
        expect(parseLyrics('[offset:500]')).toEqual([]);
    });

    it('保留文本内部空格，去掉首尾空格', () => {
        expect(parseLyrics('[00:01.000]  a  b  ')).toEqual([{ time: 1000, text: 'a  b' }]);
    });
});

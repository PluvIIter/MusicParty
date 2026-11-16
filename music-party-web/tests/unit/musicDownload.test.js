import { describe, expect, it } from 'vitest';
import { downloadName, mediaExtension } from '../../src/services/musicDownload';

describe('mediaExtension', () => {
    it('prefers the response content type', () => {
        expect(mediaExtension('audio/flac', 'https://cdn.example.com/a.mp3')).toBe('flac');
        expect(mediaExtension('audio/mpeg', 'https://cdn.example.com/a.flac')).toBe('mp3');
        expect(mediaExtension('audio/mp4', '')).toBe('m4a');
        expect(mediaExtension('video/mp4', '')).toBe('mp4');
        expect(mediaExtension('audio/ogg; codecs=opus', '')).toBe('ogg');
    });

    it('falls back to the URL file name when the content type is generic', () => {
        expect(mediaExtension('application/octet-stream', 'https://cdn.example.com/song.flac?vkey=1')).toBe('flac');
        expect(mediaExtension('', 'https://cdn.example.com/M800abc.mp3')).toBe('mp3');
        expect(mediaExtension(undefined, 'https://cdn.example.com/video.mp4')).toBe('mp4');
    });

    it('defaults to mp3 without any usable hint', () => {
        expect(mediaExtension('', 'https://cdn.example.com/media/1234')).toBe('mp3');
        expect(mediaExtension('application/octet-stream', 'not a url')).toBe('mp3');
    });
});

describe('downloadName', () => {
    it('uses the resolved extension instead of a hardcoded mp3', () => {
        expect(downloadName({ name: 'Song', artists: ['Artist'] }, 'flac')).toBe('Song - Artist.flac');
        expect(downloadName({ name: 'Song', artists: ['Artist'] }, 'mp4')).toBe('Song - Artist.mp4');
    });

    it('keeps file names usable on Windows', () => {
        expect(downloadName({ name: 'a/b:c*d?e"f<g>h|i', artists: ['x'] }, 'mp3')).toBe('a_b_c_d_e_f_g_h_i - x.mp3');
    });

    it('survives missing metadata and unknown extensions', () => {
        expect(downloadName({}, 'exe')).toBe('music - unknown.mp3');
    });
});

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { TUTORIAL_UI, TUTORIAL_STEPS, TUTORIAL_TOPICS } from '../../src/constants/tutorial';
import { tutorialText } from '../../scripts/export-tutorial.mjs';

const paragraphs = () => [
    ...Object.values(TUTORIAL_UI),
    ...TUTORIAL_STEPS.flatMap((step) => [step.title, ...step.body, ...(step.mobileBody || [])]),
    ...TUTORIAL_TOPICS.flatMap((topic) => [
        topic.title,
        topic.summary,
        ...topic.cards.flatMap((card) => [card.title, ...card.body]),
    ]),
];

describe('user tutorial content and exact text export', () => {
    it('keeps a short eight-step introduction and unique searchable topics', () => {
        expect(TUTORIAL_STEPS).toHaveLength(8);
        expect(new Set(TUTORIAL_STEPS.map((step) => step.id)).size).toBe(TUTORIAL_STEPS.length);
        expect(new Set(TUTORIAL_TOPICS.map((topic) => topic.id)).size).toBe(TUTORIAL_TOPICS.length);
        for (const step of TUTORIAL_STEPS) {
            expect(step.body.length).toBeGreaterThan(0);
            expect(step.body.length).toBeLessThanOrEqual(2);
        }
        for (const topic of TUTORIAL_TOPICS) expect(topic.cards.length).toBeGreaterThan(0);
    });
    it('exports every visible and assistive string including mobile variants', () => {
        const text = tutorialText();
        for (const value of paragraphs()) {
            expect(value.trim().length).toBeGreaterThan(0);
            expect(text).toContain(value);
        }
        expect(text).not.toMatch(/undefined|\[object Object\]/);
    });
    it('keeps the delivered TXT identical to the interface source', () => {
        expect(readFileSync('../docs/新手教程全部文案.txt', 'utf8').replace(/\r\n/g, '\n')).toBe('\uFEFF' + tutorialText());
    });
    it('covers shared controls, local records and conditional features without technical implementation details', () => {
        expect(TUTORIAL_TOPICS.map((topic) => topic.id)).toEqual([
            'start',
            'search',
            'collections',
            'likes',
            'queue',
            'modes',
            'controls',
            'recommendations',
            'personal',
            'problems',
        ]);
        expect(tutorialText()).toContain('手机先点');
        expect(tutorialText()).toContain('全局置顶');
        expect(tutorialText()).toContain('并把你的昵称改为原歌单昵称');
        expect(tutorialText()).toContain('已在队列');
        expect(tutorialText()).not.toMatch(
            /schemaVersion|STOMP|WebSocket|credentialRevision|authorizationEpoch|\/api\/v2/
        );
    });
});

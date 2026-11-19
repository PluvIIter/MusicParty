// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { availableTutorialSteps, tutorialStepAfterChange, tutorialTarget } from '../../src/domain/tutorialTargets';

const steps = [
    { id: 'welcome' },
    { id: 'search', targetId: 'search', requires: 'search' },
    { id: 'pause', targetId: 'pause', mobileTargetId: 'pause-mobile' },
    { id: 'seek', targetId: 'seek' },
];
function target(id, attributes = '') {
    const element = document.createElement('button');
    element.id = id;
    if (attributes) element.setAttribute(attributes, attributes === 'disabled' ? '' : 'false');
    element.getClientRects = () => [element.getBoundingClientRect()];
    element.getBoundingClientRect = () => ({ top: 10, left: 10, right: 50, bottom: 50, width: 40, height: 40 });
    document.body.append(element);
    return element;
}
afterEach(() => document.body.replaceChildren());

describe('single-target tutorial navigation', () => {
    it('uses only the matching desktop or mobile target', () => {
        const desktop = target('pause');
        const mobile = target('pause-mobile');
        expect(tutorialTarget(steps[2], false)).toBe(desktop);
        expect(tutorialTarget(steps[2], true)).toBe(mobile);
        mobile.remove();
        expect(tutorialTarget(steps[2], true)).toBeNull();
    });
    it('skips missing, disabled, hidden and capability-unavailable steps', () => {
        target('search');
        target('pause', 'disabled');
        target('seek', 'data-tutorial-available');
        expect(availableTutorialSteps(steps, false, () => false).map((step) => step.id)).toEqual(['welcome']);
        expect(availableTutorialSteps(steps, false).map((step) => step.id)).toEqual(['welcome', 'search']);
        document.getElementById('search').style.visibility = 'hidden';
        expect(availableTutorialSteps(steps, false).map((step) => step.id)).toEqual(['welcome']);
    });
    it('checks hidden ancestors, viewport bounds and duplicate mounted ids', () => {
        const hidden = target('pause');
        hidden.style.display = 'none';
        const visible = target('pause');
        expect(tutorialTarget(steps[2], false)).toBe(visible);
        visible.getBoundingClientRect = () => ({ top: -60, left: 10, right: 50, bottom: -20, width: 40, height: 40 });
        expect(tutorialTarget(steps[2], false)).toBeNull();
        visible.getBoundingClientRect = hidden.getBoundingClientRect;
        const parent = document.createElement('div');
        parent.style.opacity = '0';
        document.body.append(parent);
        parent.append(visible);
        expect(tutorialTarget(steps[2], false)).toBeNull();
    });
    it('preserves the current step and falls forward then backward if it disappears', () => {
        expect(tutorialStepAfterChange(steps, steps.slice(1), 'pause')).toBe('pause');
        expect(tutorialStepAfterChange(steps, [steps[0], steps[3]], 'pause')).toBe('seek');
        expect(tutorialStepAfterChange(steps, [steps[0], steps[1]], 'pause')).toBe('search');
        expect(tutorialStepAfterChange(steps, [steps[0]], 'seek')).toBe('welcome');
    });
});

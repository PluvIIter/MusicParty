export function tutorialTarget(step, mobile, root = document) {
    const id = mobile && step.mobileTargetId ? step.mobileTargetId : step.targetId;
    if (!id) return null;
    return (
        [...root.querySelectorAll(`[id="${id}"]`)].find((element) => {
            if (!element.getClientRects().length || element.matches(':disabled, [aria-disabled="true"]')) return false;
            if (element.dataset.tutorialAvailable === 'false') return false;
            for (let node = element; node; node = node.parentElement) {
                const style = getComputedStyle(node);
                if (style.visibility === 'hidden' || style.display === 'none' || style.opacity === '0') return false;
            }
            const rect = element.getBoundingClientRect();
            return (
                rect.width > 0 &&
                rect.height > 0 &&
                rect.right > 0 &&
                rect.bottom > 0 &&
                rect.left < window.innerWidth &&
                rect.top < window.innerHeight
            );
        }) || null
    );
}

export function availableTutorialSteps(steps, mobile, supports = () => true, root = document) {
    return steps.filter(
        (step) => (!step.requires || supports(step.requires)) && (!step.targetId || tutorialTarget(step, mobile, root))
    );
}

export function tutorialStepAfterChange(previous, available, currentId) {
    if (available.some((step) => step.id === currentId)) return currentId;
    const index = previous.findIndex((step) => step.id === currentId);
    return (
        previous.slice(index + 1).find((step) => available.some((item) => item.id === step.id))?.id ||
        [...previous.slice(0, index)].reverse().find((step) => available.some((item) => item.id === step.id))?.id ||
        available[0]?.id
    );
}

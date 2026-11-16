export function compactFrames() {
    let sequence = 0;
    return (state) => ({ v: 1, epoch: 'fixture', seq: ++sequence, base: sequence, full: true, state });
}

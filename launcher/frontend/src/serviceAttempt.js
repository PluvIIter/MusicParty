export function createServiceAttempt() {
  let generation = 0;
  return {
    begin: () => ++generation,
    cancel: () => ++generation,
    current: value => value === generation,
  };
}

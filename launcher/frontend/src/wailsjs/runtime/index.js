export function EventsOn(eventName, callback) {
  return window.runtime.EventsOnMultiple(eventName, callback, -1);
}

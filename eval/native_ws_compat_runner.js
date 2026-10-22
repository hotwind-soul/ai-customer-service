// Temporary test runner: lets the existing probes use Node's built-in WebSocket
// without installing the external "ws" package.
const NativeWebSocket = globalThis.WebSocket;
const Module = require('module');

class CompatibleWebSocket {
  constructor(url) {
    this.socket = new NativeWebSocket(url);
    this.handlers = new Map();
    for (const eventName of ['open', 'close', 'error', 'message']) {
      this.socket.addEventListener(eventName, async event => {
        let payload = event;
        if (eventName === 'message') {
          const value = event.data;
          if (typeof value === 'string') payload = Buffer.from(value);
          else if (value instanceof ArrayBuffer) payload = Buffer.from(value);
          else payload = Buffer.from(await value.arrayBuffer());
        }
        if (eventName === 'error' && !payload.message) payload.message = 'WebSocket error';
        for (const handler of this.handlers.get(eventName) || []) handler(payload);
      });
    }
  }

  on(eventName, handler) {
    const handlers = this.handlers.get(eventName) || [];
    handlers.push(handler);
    this.handlers.set(eventName, handlers);
    return this;
  }

  send(data) { this.socket.send(data); }
  close() { this.socket.close(); }
}

const originalLoad = Module._load;
Module._load = function(request, parent, isMain) {
  if (request === 'ws') return CompatibleWebSocket;
  return originalLoad.call(this, request, parent, isMain);
};

const target = process.argv[2];
if (!target) throw new Error('missing probe script path');
require(require('path').resolve(target));

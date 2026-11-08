import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

const source = await readFile(new URL('../../app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/PhoneGuest.kt', import.meta.url), 'utf8');
const relay = source.match(/const val RELAY_JS = """([\s\S]*?)"""/)[1];
for (const mode of ['phone', 'ui']) {
  test(`relay ${mode} handshake and transparent MCP bytes`, { timeout: 10000 }, async () => {
    const dir = await mkdtemp(join(tmpdir(), 'dsh-ui-relay-'));
    const server = createServer();
    let child;
    try {
      await writeFile(join(dir, 'relay.cjs'), relay);
      server.listen(0, '127.0.0.1'); await once(server, 'listening');
      await writeFile(join(dir, 'config.json'), JSON.stringify({ tcpPort: server.address().port, token: 'test-only' }));
      const connection = once(server, 'connection');
      child = spawn(process.execPath, [join(dir, 'relay.cjs'), join(dir, 'config.json'), ...(mode === 'ui' ? ['ui'] : [])]);
      const [socket] = await connection;
      const lines = [];
      let bytes = '';
      socket.on('data', data => {
        bytes += data;
        while (bytes.includes('\n')) { const at = bytes.indexOf('\n'); lines.push(bytes.slice(0, at)); bytes = bytes.slice(at + 1); }
        if (lines.length === 2) socket.end('{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}\n');
      });
      const outputs = [];
      child.stdout.on('data', data => outputs.push(data));
      const exit = once(child, 'exit');
      child.stdin.write('{"jsonrpc":"2.0","id":1,"method":"tools/list"}\n');
      const [code] = await exit;
      assert.equal(code, 0);
      assert.deepEqual(JSON.parse(lines[0]), mode === 'ui'
        ? { dshPhone: 1, token: 'test-only', surface: 'ui' }
        : { dshPhone: 1, token: 'test-only' });
      assert.equal(lines[1], '{"jsonrpc":"2.0","id":1,"method":"tools/list"}');
      assert.equal(Buffer.concat(outputs).toString(), '{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}\n');
    } finally { child?.kill(); server.close(); await rm(dir, { recursive: true, force: true }); }
  });
}

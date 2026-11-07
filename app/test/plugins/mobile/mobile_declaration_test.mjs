import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const context = vm.createContext({window: {}});
vm.runInContext(fs.readFileSync(new URL('../../../assets/islands/mobile_layout.js', import.meta.url), 'utf8'), context);
const validate = context.window.DshGoMobileLayout.validateDeclaration;
test('optional v1 declarations accept every documented mode and ignore unknown fields', () => {
  for (const mode of ['automatic', 'compatibility', 'desktop', 'pluginResponsive']) {
    const result = validate({v: 1, mode, compact: 360, rules: {'table.scroll': false}, future: 'ignored'});
    assert.equal(result.mode, mode);
    assert.equal(result.compact, 360);
    assert.equal(result.rules['table.scroll'], false);
    assert.equal(result.future, undefined);
  }
});
test('unsupported versions and malformed known fields are rejected', () => {
  for (const value of [null, [], {}, {v: 2, mode: 'automatic'}, {v: 1, mode: 'future'},
    {v: 1, mode: 'automatic', compact: null}, {v: 1, mode: 'automatic', rules: null}, {v: 1, mode: 'automatic', compact: 0}, {v: 1, mode: 'automatic', compact: Infinity},
    {v: 1, mode: 'automatic', rules: []}, {v: 1, mode: 'automatic', rules: {'form.stack': 'false'}}]) {
    assert.equal(validate(value), null);
  }
});

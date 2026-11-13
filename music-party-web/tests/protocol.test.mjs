import test from 'node:test';
import assert from 'node:assert/strict';
import {publicUserId, readIdentity, playbackDuration} from '../src/utils/identity.js';

test('v2 public identity never uses recovery key for display', () => {
    const me = readIdentity({id:'public',recoveryKey:'private',name:'Test',isGuest:false});
    assert.equal(me.id,'public'); assert.equal(me.recoveryKey,'private');
    assert.equal(publicUserId({recoveryKey:'secret'}),'');
});
test('legacy identity remains compatible', () => {
    assert.deepEqual(readIdentity({token:'legacy',name:'Old',isGuest:true}),
        {id:'legacy',recoveryKey:'legacy',name:'Old',isGuest:true});
});
test('trial timeline uses actual duration with legacy fallback', () => {
    assert.equal(playbackDuration({duration:240000,playableDurationMs:30000}),30000);
    assert.equal(playbackDuration({duration:240000}),240000);
});

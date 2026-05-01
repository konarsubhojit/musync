'use strict';

const { validateRoomData, isVideoRef, createRoomStore, ROOM_TTL_SECONDS } = require('../src/roomStore');
const { createApp } = require('../src/server');
const { io: ioc } = require('socket.io-client');

// ── validateRoomData ──────────────────────────────────────────────────────────

describe('validateRoomData', () => {
  const valid = {
    roomId: 'room-1',
    hostId: 'socket-abc',
    playbackState: 'PLAYING',
    isPlaying: true,
    positionMs: 0,
    currentVideo: null,
    queue: [],
    updatedAt: Date.now(),
  };

  it('accepts a fully valid RoomData object', () => {
    expect(() => validateRoomData(valid)).not.toThrow();
    expect(validateRoomData(valid)).toBe(valid);
  });

  it('accepts a PAUSED playbackState', () => {
    expect(() => validateRoomData({ ...valid, playbackState: 'PAUSED', isPlaying: false })).not.toThrow();
  });

  it('accepts null hostId', () => {
    expect(() => validateRoomData({ ...valid, hostId: null })).not.toThrow();
  });

  it('accepts a currentVideo object', () => {
    expect(() =>
      validateRoomData({ ...valid, currentVideo: { id: 'v1', title: 'Song A' } }),
    ).not.toThrow();
  });

  it('accepts a non-empty queue', () => {
    expect(() =>
      validateRoomData({ ...valid, queue: [{ id: 'v1', title: 'Song A' }] }),
    ).not.toThrow();
  });

  it('throws when data is null', () => {
    expect(() => validateRoomData(null)).toThrow(TypeError);
  });

  it('throws when data is not an object', () => {
    expect(() => validateRoomData('string')).toThrow(TypeError);
  });

  it('throws when roomId is empty', () => {
    expect(() => validateRoomData({ ...valid, roomId: '' })).toThrow(TypeError);
  });

  it('throws when roomId is not a string', () => {
    expect(() => validateRoomData({ ...valid, roomId: 42 })).toThrow(TypeError);
  });

  it('throws when hostId is a number', () => {
    expect(() => validateRoomData({ ...valid, hostId: 123 })).toThrow(TypeError);
  });

  it('throws when playbackState is invalid', () => {
    expect(() => validateRoomData({ ...valid, playbackState: 'STOPPED' })).toThrow(TypeError);
  });

  it('throws when isPlaying is not a boolean', () => {
    expect(() => validateRoomData({ ...valid, isPlaying: 1 })).toThrow(TypeError);
  });

  it('throws when isPlaying is inconsistent with playbackState (PLAYING + false)', () => {
    expect(() => validateRoomData({ ...valid, playbackState: 'PLAYING', isPlaying: false })).toThrow(TypeError);
  });

  it('throws when isPlaying is inconsistent with playbackState (PAUSED + true)', () => {
    expect(() => validateRoomData({ ...valid, playbackState: 'PAUSED', isPlaying: true })).toThrow(TypeError);
  });

  it('throws when positionMs is negative', () => {
    expect(() => validateRoomData({ ...valid, positionMs: -1 })).toThrow(TypeError);
  });

  it('throws when positionMs is NaN', () => {
    expect(() => validateRoomData({ ...valid, positionMs: NaN })).toThrow(TypeError);
  });

  it('throws when positionMs is Infinity', () => {
    expect(() => validateRoomData({ ...valid, positionMs: Infinity })).toThrow(TypeError);
  });

  it('throws when currentVideo has empty id', () => {
    expect(() =>
      validateRoomData({ ...valid, currentVideo: { id: '', title: 'Song' } }),
    ).toThrow(TypeError);
  });

  it('throws when currentVideo has empty title', () => {
    expect(() =>
      validateRoomData({ ...valid, currentVideo: { id: 'v1', title: '' } }),
    ).toThrow(TypeError);
  });

  it('throws when currentVideo is missing title', () => {
    expect(() =>
      validateRoomData({ ...valid, currentVideo: { id: 'v1' } }),
    ).toThrow(TypeError);
  });

  it('throws when queue contains an item with empty id', () => {
    expect(() =>
      validateRoomData({ ...valid, queue: [{ id: '', title: 'Song' }] }),
    ).toThrow(TypeError);
  });

  it('throws when queue contains an item with empty title', () => {
    expect(() =>
      validateRoomData({ ...valid, queue: [{ id: 'v1', title: '' }] }),
    ).toThrow(TypeError);
  });

  it('throws when queue is not an array', () => {
    expect(() => validateRoomData({ ...valid, queue: null })).toThrow(TypeError);
  });

  it('throws when updatedAt is missing', () => {
    const noUpdatedAt = Object.fromEntries(
      Object.entries(valid).filter(([k]) => k !== 'updatedAt'),
    );
    expect(() => validateRoomData(noUpdatedAt)).toThrow(TypeError);
  });

  it('throws when updatedAt is NaN', () => {
    expect(() => validateRoomData({ ...valid, updatedAt: NaN })).toThrow(TypeError);
  });

  it('throws when updatedAt is negative', () => {
    expect(() => validateRoomData({ ...valid, updatedAt: -1 })).toThrow(TypeError);
  });

  it('throws when updatedAt is a string', () => {
    expect(() => validateRoomData({ ...valid, updatedAt: '12345' })).toThrow(TypeError);
  });
});

// ── ROOM_TTL_SECONDS ──────────────────────────────────────────────────────────

describe('ROOM_TTL_SECONDS', () => {
  it('is 14400 seconds (4 hours)', () => {
    expect(ROOM_TTL_SECONDS).toBe(14400);
  });
});

// ── isVideoRef ────────────────────────────────────────────────────────────────

describe('isVideoRef', () => {
  it('returns true for a valid VideoRef', () => {
    expect(isVideoRef({ id: 'v1', title: 'Song' })).toBe(true);
  });

  it('returns false for null', () => {
    expect(isVideoRef(null)).toBe(false);
  });

  it('returns false when id is empty', () => {
    expect(isVideoRef({ id: '', title: 'Song' })).toBe(false);
  });

  it('returns false when title is empty', () => {
    expect(isVideoRef({ id: 'v1', title: '' })).toBe(false);
  });

  it('returns false when id is missing', () => {
    expect(isVideoRef({ title: 'Song' })).toBe(false);
  });
});

// ── createRoomStore (unit tests) ──────────────────────────────────────────────

describe('createRoomStore (in-memory)', () => {
  it('returns null for an unknown room', async () => {
    const store = createRoomStore();
    expect(await store.getRoom('unknown')).toBeNull();
  });

  it('stores and retrieves valid RoomData', async () => {
    const store = createRoomStore();
    const room = {
      roomId: 'r1',
      hostId: null,
      playbackState: 'PAUSED',
      isPlaying: false,
      positionMs: 0,
      currentVideo: null,
      queue: [],
      updatedAt: Date.now(),
    };
    await store.setRoom('r1', room);
    expect(await store.getRoom('r1')).toEqual(room);
  });

  it('rejects invalid RoomData in setRoom', async () => {
    const store = createRoomStore();
    await expect(store.setRoom('r1', { roomId: '' })).rejects.toThrow(TypeError);
  });

  it('deletes a room', async () => {
    const store = createRoomStore();
    const room = {
      roomId: 'r-del',
      hostId: null,
      playbackState: 'PAUSED',
      isPlaying: false,
      positionMs: 0,
      currentVideo: null,
      queue: [],
      updatedAt: Date.now(),
    };
    await store.setRoom('r-del', room);
    await store.deleteRoom('r-del');
    expect(await store.getRoom('r-del')).toBeNull();
  });
});

// ── Helpers for integration tests ─────────────────────────────────────────────

/**
 * Creates a fresh, isolated in-memory room store for each test suite.
 * Passing this into createApp ensures tests never accidentally hit Redis.
 */
function makeTestStore() {
  return createRoomStore(); // no env vars in test environment → always in-memory
}

// ── Redis schema fields in join_room state ────────────────────────────────────

describe('Redis schema fields', () => {
  let httpServer, io, serverUrl;

  function connect() {
    return new Promise((resolve) => {
      const socket = ioc(serverUrl, { forceNew: true });
      socket.on('connect', () => resolve(socket));
    });
  }

  function joinRoom(socket, roomId) {
    return new Promise((resolve, reject) => {
      socket.emit('join_room', roomId, (ack) => {
        if (ack && ack.error) reject(new Error(ack.error));
        else resolve(ack);
      });
    });
  }

  beforeAll((done) => {
    // Inject an explicit in-memory store so these tests are hermetic regardless
    // of whether UPSTASH_REDIS_REST_URL/TOKEN happen to be set in the environment.
    ({ httpServer, io } = createApp({ roomStore: makeTestStore() }));
    httpServer.listen(0, () => {
      const { port } = httpServer.address();
      serverUrl = `http://localhost:${port}`;
      done();
    });
  });

  afterAll((done) => {
    io.close(done);
  });

  it('state includes hostId, playbackState, and updatedAt after PLAY', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-play');
    const before = Date.now();
    alice.emit('PLAY', { roomId: 'schema-play', positionMs: 1000 });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-play');

    expect(ack.state).toMatchObject({
      roomId: 'schema-play',
      hostId: alice.id,
      playbackState: 'PLAYING',
      isPlaying: true,
      positionMs: 1000,
      currentVideo: null,
      queue: [],
    });
    // updatedAt should be a reasonable timestamp
    expect(typeof ack.state.updatedAt).toBe('number');
    expect(ack.state.updatedAt).toBeGreaterThanOrEqual(before);

    alice.disconnect();
    bob.disconnect();
  });

  it('playbackState is PAUSED after PAUSE event', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-pause');
    alice.emit('PAUSE', { roomId: 'schema-pause', positionMs: 5000 });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-pause');

    expect(ack.state).toMatchObject({
      playbackState: 'PAUSED',
      isPlaying: false,
      positionMs: 5000,
    });

    alice.disconnect();
    bob.disconnect();
  });

  it('hostId is preserved when a second socket sends PLAY', async () => {
    const [alice, bob] = await Promise.all([connect(), connect()]);
    await joinRoom(alice, 'schema-host');
    await joinRoom(bob, 'schema-host');

    // Alice triggers first PLAY — she becomes the host
    alice.emit('PLAY', { roomId: 'schema-host', positionMs: 0 });
    await new Promise((r) => setTimeout(r, 50));

    // Bob then sends PLAY — hostId must still be alice's ID
    bob.emit('PLAY', { roomId: 'schema-host', positionMs: 2000 });
    await new Promise((r) => setTimeout(r, 50));

    const carol = await connect();
    const ack = await joinRoom(carol, 'schema-host');

    expect(ack.state.hostId).toBe(alice.id);

    alice.disconnect();
    bob.disconnect();
    carol.disconnect();
  });

  it('SEEK preserves isPlaying=true and original hostId', async () => {
    const [alice, bob] = await Promise.all([connect(), connect()]);
    await joinRoom(alice, 'schema-seek-playing');
    await joinRoom(bob, 'schema-seek-playing');

    alice.emit('PLAY', { roomId: 'schema-seek-playing', positionMs: 1000 });
    await new Promise((r) => setTimeout(r, 50));

    // Bob seeks — must not change isPlaying or hostId
    bob.emit('SEEK', { roomId: 'schema-seek-playing', positionMs: 45000 });
    await new Promise((r) => setTimeout(r, 50));

    const carol = await connect();
    const ack = await joinRoom(carol, 'schema-seek-playing');

    expect(ack.state).toMatchObject({
      hostId: alice.id,
      playbackState: 'PLAYING',
      isPlaying: true,
      positionMs: 45000,
    });

    alice.disconnect();
    bob.disconnect();
    carol.disconnect();
  });

  it('SEEK preserves isPlaying=false when room was PAUSED', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-seek-paused');

    alice.emit('PAUSE', { roomId: 'schema-seek-paused', positionMs: 3000 });
    await new Promise((r) => setTimeout(r, 50));

    alice.emit('SEEK', { roomId: 'schema-seek-paused', positionMs: 20000 });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-seek-paused');

    expect(ack.state).toMatchObject({
      playbackState: 'PAUSED',
      isPlaying: false,
      positionMs: 20000,
    });

    alice.disconnect();
    bob.disconnect();
  });

  it('queue and currentVideo are persisted via QUEUE_UPDATED', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-queue');

    // Establish room state first so QUEUE_UPDATED has an existing room to update
    alice.emit('PLAY', { roomId: 'schema-queue', positionMs: 0 });
    await new Promise((r) => setTimeout(r, 50));

    const tracks = [
      { id: 'v1', title: 'Track 1' },
      { id: 'v2', title: 'Track 2' },
    ];
    alice.emit('QUEUE_UPDATED', { roomId: 'schema-queue', queue: tracks });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-queue');

    expect(ack.state.queue).toEqual(tracks);
    // currentVideo is derived from the first item in the queue
    expect(ack.state.currentVideo).toEqual({ id: 'v1', title: 'Track 1' });

    alice.disconnect();
    bob.disconnect();
  });

  it('QUEUE_UPDATED filters out invalid items; only valid entries are persisted', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-queue-filter');

    alice.emit('PLAY', { roomId: 'schema-queue-filter', positionMs: 0 });
    await new Promise((r) => setTimeout(r, 50));

    // Mix valid and invalid items
    const mixedQueue = [
      { id: 'v1', title: 'Valid Track' },
      { id: '', title: 'Empty id — invalid' },
      { id: 'v2', title: '' }, // empty title — invalid
      null,
      { title: 'Missing id — invalid' },
      { id: 'v3', title: 'Another Valid' },
    ];
    alice.emit('QUEUE_UPDATED', { roomId: 'schema-queue-filter', queue: mixedQueue });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-queue-filter');

    // Only the two valid entries should be stored
    expect(ack.state.queue).toEqual([
      { id: 'v1', title: 'Valid Track' },
      { id: 'v3', title: 'Another Valid' },
    ]);
    expect(ack.state.currentVideo).toEqual({ id: 'v1', title: 'Valid Track' });

    alice.disconnect();
    bob.disconnect();
  });

  it('QUEUE_UPDATED before any PLAY/PAUSE bootstraps PAUSED room state', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-queue-bootstrap');

    const tracks = [{ id: 'v1', title: 'Track 1' }];
    alice.emit('QUEUE_UPDATED', { roomId: 'schema-queue-bootstrap', queue: tracks });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-queue-bootstrap');

    expect(ack.state).toMatchObject({
      playbackState: 'PAUSED',
      isPlaying: false,
      positionMs: 0,
      queue: tracks,
      currentVideo: { id: 'v1', title: 'Track 1' },
    });

    alice.disconnect();
    bob.disconnect();
  });

  it('clearing the queue via QUEUE_UPDATED resets currentVideo to null', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-queue-clear');

    alice.emit('PLAY', { roomId: 'schema-queue-clear', positionMs: 0 });
    await new Promise((r) => setTimeout(r, 50));

    // First populate the queue
    alice.emit('QUEUE_UPDATED', {
      roomId: 'schema-queue-clear',
      queue: [{ id: 'v1', title: 'Track 1' }],
    });
    await new Promise((r) => setTimeout(r, 50));

    // Then clear it
    alice.emit('QUEUE_UPDATED', { roomId: 'schema-queue-clear', queue: [] });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-queue-clear');

    expect(ack.state.queue).toEqual([]);
    expect(ack.state.currentVideo).toBeNull();

    alice.disconnect();
    bob.disconnect();
  });

  it('state is null for a brand-new room (before any PLAY/PAUSE)', async () => {
    const alice = await connect();
    const ack = await joinRoom(alice, 'schema-new');

    expect(ack.state).toBeNull();

    alice.disconnect();
  });
});

// ── cleanupRoomState (mock store) ─────────────────────────────────────────────

describe('cleanupRoomState', () => {
  let httpServer, io, serverUrl;

  function connect() {
    return new Promise((resolve) => {
      const socket = ioc(serverUrl, { forceNew: true });
      socket.on('connect', () => resolve(socket));
    });
  }

  function joinRoom(socket, roomId) {
    return new Promise((resolve, reject) => {
      socket.emit('join_room', roomId, (ack) => {
        if (ack && ack.error) reject(new Error(ack.error));
        else resolve(ack);
      });
    });
  }

  function leaveRoom(socket, roomId) {
    return new Promise((resolve, reject) => {
      socket.emit('leave_room', roomId, (ack) => {
        if (ack && ack.error) reject(new Error(ack.error));
        else resolve(ack);
      });
    });
  }

  let fakeStore;

  beforeEach((done) => {
    // Fresh mock store for each test
    const inner = createRoomStore();
    fakeStore = {
      getRoom: jest.fn((...args) => inner.getRoom(...args)),
      setRoom: jest.fn((...args) => inner.setRoom(...args)),
      deleteRoom: jest.fn((...args) => inner.deleteRoom(...args)),
    };
    ({ httpServer, io } = createApp({ roomStore: fakeStore }));
    httpServer.listen(0, () => {
      const { port } = httpServer.address();
      serverUrl = `http://localhost:${port}`;
      done();
    });
  });

  afterEach((done) => {
    io.close(done);
  });

  it('does NOT call deleteRoom when one member remains after another leaves', async () => {
    const [alice, bob] = await Promise.all([connect(), connect()]);
    await joinRoom(alice, 'cleanup-room');
    await joinRoom(bob, 'cleanup-room');

    fakeStore.deleteRoom.mockClear();
    await leaveRoom(alice, 'cleanup-room');
    await new Promise((r) => setTimeout(r, 50));

    expect(fakeStore.deleteRoom).not.toHaveBeenCalled();

    alice.disconnect();
    bob.disconnect();
  });

  it('calls deleteRoom once when the last member leaves via leave_room', async () => {
    const alice = await connect();
    await joinRoom(alice, 'cleanup-last');

    fakeStore.deleteRoom.mockClear();
    await leaveRoom(alice, 'cleanup-last');
    await new Promise((r) => setTimeout(r, 50));

    expect(fakeStore.deleteRoom).toHaveBeenCalledTimes(1);
    expect(fakeStore.deleteRoom).toHaveBeenCalledWith('cleanup-last');

    alice.disconnect();
  });

  it('handles deleteRoom throwing without crashing the server', async () => {
    fakeStore.deleteRoom.mockRejectedValueOnce(new Error('Redis timeout'));
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});

    const alice = await connect();
    await joinRoom(alice, 'cleanup-throw');
    await leaveRoom(alice, 'cleanup-throw');
    await new Promise((r) => setTimeout(r, 50));

    // Server still running — we can connect again
    const bob = await connect();
    expect(bob.connected).toBe(true);

    errorSpy.mockRestore();
    alice.disconnect();
    bob.disconnect();
  });
});


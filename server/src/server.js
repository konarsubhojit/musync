/**
 * Creates and configures the Express app and Socket.IO server.
 *
 * Exported as a factory so it can be instantiated in tests without
 * binding to a fixed port.
 */

'use strict';

const express = require('express');
const { createServer } = require('http');
const { Server } = require('socket.io');
const { createRoomStore, isVideoRef } = require('./roomStore');

/**
 * @typedef {object} AppBundle
 * @property {import('express').Application} app - The Express application.
 * @property {import('http').Server} httpServer - The underlying HTTP server.
 * @property {import('socket.io').Server} io - The Socket.IO server instance.
 */

/**
 * @typedef {import('./roomStore').RoomData} RoomData
 */

/**
 * Builds the Express + Socket.IO stack.
 *
 * @param {object}  [options]
 * @param {ReturnType<import('./roomStore').createRoomStore>} [options.roomStore]
 *   Optional room store override — pass a custom store in tests or
 *   leave unset to auto-select Redis (when env vars are present) or
 *   the in-memory fallback.
 * @returns {AppBundle}
 */
function createApp(options = {}) {
  const roomStore = options.roomStore ?? createRoomStore();

  const app = express();

  // ── Health check ──────────────────────────────────────────────────────────
  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  const httpServer = createServer(app);

  const io = new Server(httpServer, {
    cors: {
      origin: '*',
      methods: ['GET', 'POST'],
    },
  });

  /**
   * Returns `pos` when it is a finite, non-negative number; otherwise `null`.
   * @param {unknown} pos
   * @returns {number|null}
   */
  function validPositionMs(pos) {
    return typeof pos === 'number' && Number.isFinite(pos) && pos >= 0 ? pos : null;
  }

  /**
   * Persists the given playback state delta for a room and returns the updated
   * RoomData.  Only called when the socket has already verified membership.
   *
   * The `socketId` is stored as `hostId` the first time state is created for a
   * room (i.e. the first socket to trigger a PLAY/PAUSE/SEEK becomes the host).
   *
   * @param {string}  socketId
   * @param {string}  roomId
   * @param {boolean} isPlaying
   * @param {number}  positionMs
   * @returns {Promise<RoomData>}
   */
  async function updateRoomState(socketId, roomId, isPlaying, positionMs) {
    try {
      const existing = await roomStore.getRoom(roomId);
      const roomData = {
        roomId,
        hostId: existing?.hostId ?? socketId,
        playbackState: isPlaying ? 'PLAYING' : 'PAUSED',
        isPlaying,
        positionMs,
        currentVideo: existing?.currentVideo ?? null,
        queue: existing?.queue ?? [],
        updatedAt: Date.now(),
      };
      await roomStore.setRoom(roomId, roomData);
      return roomData;
    } catch (err) {
      console.error(`[roomStore] updateRoomState failed  room=${roomId}:`, err);
      throw err;
    }
  }

  /**
   * Deletes room state when the room will have no remaining members after
   * `leavingSocketId` departs.  Pass `null` when the socket has already left.
   *
   * This is intentionally fire-and-forget from the event handlers that call it.
   * The worst case of the inherent async race (a new joiner arrives between the
   * fetchSockets call and the delete) is that state is spuriously cleared for a
   * brief moment; the joining client always receives current state in the
   * join_room ack, so it can recover immediately.
   *
   * NOTE: `updateRoomState` and the queue handler use a read-then-write pattern
   * (getRoom → setRoom) that is not atomic.  Concurrent events from different
   * sockets in the same room could overwrite each other's changes.  For the
   * current single-server deployment this race is extremely unlikely and the
   * original in-memory Map had the same behaviour.  A future improvement can
   * replace these with Redis Lua scripts or WATCH/MULTI/EXEC transactions.
   *
   * @param {string}      roomId
   * @param {string|null} leavingSocketId
   */
  async function cleanupRoomState(roomId, leavingSocketId = null) {
    try {
      const sockets = await io.in(roomId).fetchSockets();
      const remaining = leavingSocketId
        ? sockets.filter((s) => s.id !== leavingSocketId)
        : sockets;
      if (remaining.length === 0) {
        await roomStore.deleteRoom(roomId);
      }
    } catch (err) {
      console.error(`[roomStore] cleanupRoomState failed for room=${roomId}:`, err);
    }
  }

  // ── Socket.IO ─────────────────────────────────────────────────────────────
  io.on('connection', (socket) => {
    console.log(`[socket] connected  id=${socket.id}`);

    // ── join_room ──────────────────────────────────────────────────────────
    socket.on('join_room', async (roomId, ack) => {
      if (typeof roomId !== 'string' || roomId.trim() === '') {
        if (typeof ack === 'function') ack({ error: 'invalid roomId' });
        return;
      }
      socket.join(roomId);
      console.log(`[socket] joined     id=${socket.id}  room=${roomId}`);
      socket.to(roomId).emit('peer_joined', { socketId: socket.id });
      if (typeof ack === 'function') {
        try {
          const roomData = await roomStore.getRoom(roomId);
          ack({ ok: true, state: roomData ?? null });
        } catch (err) {
          console.error(`[socket] join_room failed  id=${socket.id}  room=${roomId}:`, err);
          ack({ error: 'failed to load room state' });
        }
      }
    });

    // ── leave_room ─────────────────────────────────────────────────────────
    socket.on('leave_room', (roomId, ack) => {
      if (typeof roomId !== 'string' || roomId.trim() === '') {
        if (typeof ack === 'function') ack({ error: 'invalid roomId' });
        return;
      }
      socket.leave(roomId);
      console.log(`[socket] left       id=${socket.id}  room=${roomId}`);
      socket.to(roomId).emit('peer_left', { socketId: socket.id });
      if (typeof ack === 'function') ack({ ok: true });
      cleanupRoomState(roomId);
    });

    // ── SYNC_HEARTBEAT ─────────────────────────────────────────────────────
    // Relays the host's playback position to every other member of the room.
    // Expected payload: { roomId: string, hostPositionMs: number, hostTimestamp: number }
    socket.on('SYNC_HEARTBEAT', (payload) => {
      const { roomId, ...heartbeat } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') {
        return;
      }
      socket.to(roomId).emit('SYNC_HEARTBEAT', heartbeat);
    });

    // ── QUEUE_UPDATED ──────────────────────────────────────────────────────
    // Relays the updated track queue to every other member of the room and
    // persists it in the room store so late joiners receive the full queue.
    // Expected payload: { roomId: string, queue: VideoRef[] }
    socket.on('QUEUE_UPDATED', async (payload) => {
      const { roomId, queue } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') {
        return;
      }
      if (!socket.rooms.has(roomId)) {
        return;
      }
      if (!Array.isArray(queue)) {
        return;
      }
      try {
        // Discard items that don't satisfy the VideoRef shape so validation passes.
        const normalized = queue.filter(isVideoRef).map(({ id, title }) => ({ id, title }));
        const existing = await roomStore.getRoom(roomId);
        // If no PLAY/PAUSE/SEEK has run yet, bootstrap a PAUSED room state so
        // the queue is never silently dropped.
        const base = existing ?? {
          roomId,
          hostId: null,
          playbackState: 'PAUSED',
          isPlaying: false,
          positionMs: 0,
          currentVideo: null,
          queue: [],
          updatedAt: Date.now(),
        };
        await roomStore.setRoom(roomId, {
          ...base,
          queue: normalized,
          // Clearing the queue resets currentVideo to null.
          currentVideo: normalized[0] ?? null,
          updatedAt: Date.now(),
        });
        // Broadcast the normalized queue so peers see the same data as the store.
        socket.to(roomId).emit('QUEUE_UPDATED', normalized);
      } catch (err) {
        console.error(`[socket] QUEUE_UPDATED failed  id=${socket.id}  room=${roomId}:`, err);
      }
    });

    // ── PLAY ───────────────────────────────────────────────────────────────
    // Tells every other room member to start playback at the given position.
    // Expected payload: { roomId: string, positionMs: number }
    socket.on('PLAY', async (payload) => {
      const { roomId, positionMs } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') return;
      if (!socket.rooms.has(roomId)) return;
      const pos = validPositionMs(positionMs);
      if (pos === null) return;
      try {
        const roomData = await updateRoomState(socket.id, roomId, true, pos);
        socket.to(roomId).emit('PLAY', { positionMs: roomData.positionMs });
      } catch (err) {
        console.error(`[socket] PLAY failed  id=${socket.id}  room=${roomId}:`, err);
      }
    });

    // ── PAUSE ──────────────────────────────────────────────────────────────
    // Tells every other room member to pause playback at the given position.
    // Expected payload: { roomId: string, positionMs: number }
    socket.on('PAUSE', async (payload) => {
      const { roomId, positionMs } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') return;
      if (!socket.rooms.has(roomId)) return;
      const pos = validPositionMs(positionMs);
      if (pos === null) return;
      try {
        const roomData = await updateRoomState(socket.id, roomId, false, pos);
        socket.to(roomId).emit('PAUSE', { positionMs: roomData.positionMs });
      } catch (err) {
        console.error(`[socket] PAUSE failed  id=${socket.id}  room=${roomId}:`, err);
      }
    });

    // ── SEEK ───────────────────────────────────────────────────────────────
    // Tells every other room member to seek to the given position.
    // Expected payload: { roomId: string, positionMs: number }
    socket.on('SEEK', async (payload) => {
      const { roomId, positionMs } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') return;
      if (!socket.rooms.has(roomId)) return;
      const pos = validPositionMs(positionMs);
      if (pos === null) return;
      try {
        // Preserve the current isPlaying flag; default to false (paused) when
        // no prior PLAY/PAUSE event has established room state yet.
        const existing = await roomStore.getRoom(roomId);
        const isPlaying = existing?.isPlaying ?? false;
        const roomData = await updateRoomState(socket.id, roomId, isPlaying, pos);
        socket.to(roomId).emit('SEEK', { positionMs: roomData.positionMs });
      } catch (err) {
        console.error(`[socket] SEEK failed  id=${socket.id}  room=${roomId}:`, err);
      }
    });

    // ── disconnecting ──────────────────────────────────────────────────────
    // Fires while the socket is still a member of its rooms, so we can
    // clean up state for any room that becomes empty as a result.
    socket.on('disconnecting', () => {
      for (const roomId of socket.rooms) {
        // Skip the socket's own personal room (its ID).
        if (roomId === socket.id) continue;
        // Pass socket.id so it is excluded when checking remaining members.
        cleanupRoomState(roomId, socket.id);
      }
    });

    // ── disconnect ─────────────────────────────────────────────────────────
    socket.on('disconnect', (reason) => {
      console.log(`[socket] disconnected id=${socket.id}  reason=${reason}`);
    });
  });

  return { app, httpServer, io };
}

module.exports = { createApp };

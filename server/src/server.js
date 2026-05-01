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

/**
 * @typedef {object} AppBundle
 * @property {import('express').Application} app - The Express application.
 * @property {import('http').Server} httpServer - The underlying HTTP server.
 * @property {import('socket.io').Server} io - The Socket.IO server instance.
 */

/**
 * @typedef {object} RoomState
 * @property {boolean} isPlaying   - Whether the room is currently playing.
 * @property {number}  positionMs  - Last known playback position in milliseconds.
 * @property {number}  updatedAt   - Wall-clock timestamp (ms) when state was last updated.
 */

/**
 * Builds the Express + Socket.IO stack.
 *
 * @returns {AppBundle}
 */
function createApp() {
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
   * In-memory room state, scoped to this app instance to prevent cross-test
   * or cross-server state leakage. Keyed by roomId.
   * @type {Map<string, RoomState>}
   */
  const roomStates = new Map();

  /**
   * Returns `pos` when it is a finite, non-negative number; otherwise `null`.
   * @param {unknown} pos
   * @returns {number|null}
   */
  function validPositionMs(pos) {
    return typeof pos === 'number' && Number.isFinite(pos) && pos >= 0 ? pos : null;
  }

  /**
   * Persists the given state delta for a room and returns the updated state.
   * Only called when the socket has already verified membership in `roomId`.
   *
   * @param {string}  roomId
   * @param {boolean} isPlaying
   * @param {number}  positionMs
   * @returns {RoomState}
   */
  function updateRoomState(roomId, isPlaying, positionMs) {
    const state = { isPlaying, positionMs, updatedAt: Date.now() };
    roomStates.set(roomId, state);
    return state;
  }

  /**
   * Removes the room state entry if the room will have no remaining members
   * after `leavingSocketId` departs.  Pass `null` when the socket has already
   * left (e.g. after `socket.leave()`).
   *
   * This is intentionally fire-and-forget from the event handlers that call it.
   * The worst case of the inherent async race (a new joiner arrives between the
   * fetchSockets call and the delete) is that state is spuriously cleared for a
   * brief moment; the joining client always receives current state in the
   * join_room ack, so it can recover immediately.
   *
   * @param {string}      roomId
   * @param {string|null} leavingSocketId
   */
  async function cleanupRoomState(roomId, leavingSocketId = null) {
    const sockets = await io.in(roomId).fetchSockets();
    const remaining = leavingSocketId
      ? sockets.filter((s) => s.id !== leavingSocketId)
      : sockets;
    if (remaining.length === 0) {
      roomStates.delete(roomId);
    }
  }

  // ── Socket.IO ─────────────────────────────────────────────────────────────
  io.on('connection', (socket) => {
    console.log(`[socket] connected  id=${socket.id}`);

    // ── join_room ──────────────────────────────────────────────────────────
    socket.on('join_room', (roomId, ack) => {
      if (typeof roomId !== 'string' || roomId.trim() === '') {
        if (typeof ack === 'function') ack({ error: 'invalid roomId' });
        return;
      }
      socket.join(roomId);
      console.log(`[socket] joined     id=${socket.id}  room=${roomId}`);
      socket.to(roomId).emit('peer_joined', { socketId: socket.id });
      if (typeof ack === 'function') {
        ack({ ok: true, state: roomStates.get(roomId) ?? null });
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
    // Relays the updated track queue to every other member of the room.
    // Expected payload: { roomId: string, queue: Track[] }
    socket.on('QUEUE_UPDATED', (payload) => {
      const { roomId, queue } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') {
        return;
      }
      socket.to(roomId).emit('QUEUE_UPDATED', queue);
    });

    // ── PLAY ───────────────────────────────────────────────────────────────
    // Tells every other room member to start playback at the given position.
    // Expected payload: { roomId: string, positionMs: number }
    socket.on('PLAY', (payload) => {
      const { roomId, positionMs } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') return;
      if (!socket.rooms.has(roomId)) return;
      const pos = validPositionMs(positionMs);
      if (pos === null) return;
      const state = updateRoomState(roomId, true, pos);
      socket.to(roomId).emit('PLAY', { positionMs: state.positionMs });
    });

    // ── PAUSE ──────────────────────────────────────────────────────────────
    // Tells every other room member to pause playback at the given position.
    // Expected payload: { roomId: string, positionMs: number }
    socket.on('PAUSE', (payload) => {
      const { roomId, positionMs } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') return;
      if (!socket.rooms.has(roomId)) return;
      const pos = validPositionMs(positionMs);
      if (pos === null) return;
      const state = updateRoomState(roomId, false, pos);
      socket.to(roomId).emit('PAUSE', { positionMs: state.positionMs });
    });

    // ── SEEK ───────────────────────────────────────────────────────────────
    // Tells every other room member to seek to the given position.
    // Expected payload: { roomId: string, positionMs: number }
    socket.on('SEEK', (payload) => {
      const { roomId, positionMs } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') return;
      if (!socket.rooms.has(roomId)) return;
      const pos = validPositionMs(positionMs);
      if (pos === null) return;
      // Preserve the current isPlaying flag; default to false (paused) when
      // no prior PLAY/PAUSE event has established room state yet.
      const isPlaying = roomStates.get(roomId)?.isPlaying ?? false;
      const state = updateRoomState(roomId, isPlaying, pos);
      socket.to(roomId).emit('SEEK', { positionMs: state.positionMs });
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

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
      if (typeof ack === 'function') ack({ ok: true });
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

    // ── disconnect ─────────────────────────────────────────────────────────
    socket.on('disconnect', (reason) => {
      console.log(`[socket] disconnected id=${socket.id}  reason=${reason}`);
    });
  });

  return { app, httpServer, io };
}

module.exports = { createApp };

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

  // ── Android App Links: assetlinks.json ────────────────────────────────────
  // Served at the well-known location Android fetches when verifying
  // `<intent-filter android:autoVerify="true">` links of the form
  // `https://<host>/room/<id>`.  When this route returns a valid manifest the
  // OS routes shared invite links straight into the app without a chooser, so
  // the Socket.IO API and the invite-link host can be the same domain.
  //
  // Configuration (env vars):
  //   ANDROID_APP_PACKAGE_NAME      Application id (default: com.musync)
  //   ANDROID_APP_SHA256_FINGERPRINTS
  //                                 Comma-separated list of SHA-256 signing
  //                                 certificate fingerprints (uppercase hex,
  //                                 colon-separated).  When unset the route
  //                                 responds 404 so we never serve an empty
  //                                 or incorrect manifest.
  app.get('/.well-known/assetlinks.json', (_req, res) => {
    const packageName = process.env.ANDROID_APP_PACKAGE_NAME ?? 'com.musync';
    const fingerprints = (process.env.ANDROID_APP_SHA256_FINGERPRINTS ?? '')
      .split(',')
      .map((fp) => fp.trim())
      .filter((fp) => fp.length > 0);

    if (fingerprints.length === 0) {
      res.status(404).json({ error: 'assetlinks not configured' });
      return;
    }

    res.json([
      {
        relation: ['delegate_permission/common.handle_all_urls'],
        target: {
          namespace: 'android_app',
          package_name: packageName,
          sha256_cert_fingerprints: fingerprints,
        },
      },
    ]);
  });

  // ── Invite-link landing page ──────────────────────────────────────────────
  // The Android app advertises an App Link for `https://<host>/room/<id>`.
  // When the OS fails to verify the link (assetlinks not yet served, app not
  // installed, browser navigation, etc.) the URL is fetched over HTTP and the
  // user lands here.  We return a minimal page that:
  //   * Validates the roomId looks reasonable
  //   * Re-attempts the deep link via `<a href>` so installed-app users can
  //     bounce into the app without re-typing the URL
  //   * Tells visitors how to get the app otherwise
  app.get('/room/:roomId', (req, res) => {
    const { roomId } = req.params;
    // Mirror the validation used on the socket side: non-empty trimmed string,
    // restricted to characters that are safe to embed in HTML/URL contexts.
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(roomId)) {
      res.status(400).type('text/plain').send('Invalid room id');
      return;
    }
    const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MuSync · Join room ${roomId}</title>
<style>
  body { font-family: -apple-system, system-ui, sans-serif; max-width: 32rem;
         margin: 4rem auto; padding: 0 1rem; line-height: 1.5; color: #222; }
  h1   { margin: 0 0 1rem; font-size: 1.5rem; }
  code { background: #f4f4f4; padding: 0.1rem 0.4rem; border-radius: 4px; }
  a.button { display: inline-block; margin-top: 1rem; padding: 0.6rem 1rem;
             background: #1f6feb; color: white; border-radius: 6px;
             text-decoration: none; font-weight: 600; }
</style>
</head>
<body>
  <h1>You're invited to a MuSync room</h1>
  <p>Room: <code>${roomId}</code></p>
  <p>If you have the MuSync app installed it should open automatically.
     If not, tap the button below.</p>
  <p><a class="button" href="/room/${roomId}">Open in MuSync</a></p>
</body>
</html>`;
    res.type('text/html').send(html);
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

  /**
   * Returns a non-empty display name from `raw`, falling back to `'Someone'`
   * when the value is absent or blank.
   * @param {unknown} raw
   * @returns {string}
   */
  function sanitiseSenderName(raw) {
    return typeof raw === 'string' && raw.trim() !== '' ? raw.trim() : 'Someone';
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

    // ── end_session ────────────────────────────────────────────────────────
    // Allows the host to close the room for all participants.  Broadcasts a
    // ROOM_CLOSED event to every member (including the host) and deletes the
    // persisted room state.
    socket.on('end_session', async (roomId, ack) => {
      if (typeof roomId !== 'string' || roomId.trim() === '') {
        if (typeof ack === 'function') ack({ error: 'invalid roomId' });
        return;
      }
      if (!socket.rooms.has(roomId)) {
        if (typeof ack === 'function') ack({ error: 'not in room' });
        return;
      }
      console.log(`[socket] end_session id=${socket.id}  room=${roomId}`);
      // Notify all members (including the host) that the room is closed.
      io.to(roomId).emit('ROOM_CLOSED');
      // Remove persisted state so late joiners don't see stale data.
      try {
        await roomStore.deleteRoom(roomId);
      } catch (err) {
        console.error(`[socket] end_session deleteRoom failed  room=${roomId}:`, err);
      }
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

    // ── CHAT_MESSAGE ───────────────────────────────────────────────────────
    // Relays a text message from one room member to all other members.
    // Expected payload: { roomId: string, text: string, senderId: string, senderName: string }
    socket.on('CHAT_MESSAGE', (payload) => {
      const { roomId, text, senderId, senderName } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') return;
      if (!socket.rooms.has(roomId)) return;
      if (typeof text !== 'string' || text.trim() === '') return;
      if (typeof senderId !== 'string' || senderId.trim() === '') return;
      const name = sanitiseSenderName(senderName);
      socket.to(roomId).emit('CHAT_MESSAGE', {
        senderId: senderId.trim(),
        senderName: name,
        text: text.trim(),
      });
    });

    // ── REACTION ───────────────────────────────────────────────────────────
    // Relays an ephemeral emoji reaction to all other members of the room.
    // Expected payload: { roomId: string, emoji: string }
    socket.on('REACTION', (payload) => {
      const { roomId, emoji } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') return;
      if (!socket.rooms.has(roomId)) return;
      if (typeof emoji !== 'string' || emoji.trim() === '') return;
      socket.to(roomId).emit('REACTION', { emoji: emoji.trim() });
    });

    // ── TYPING ─────────────────────────────────────────────────────────────
    // Notifies other room members that a participant is composing a message.
    // Expected payload: { roomId: string, senderId: string, senderName: string }
    socket.on('TYPING', (payload) => {
      const { roomId, senderId, senderName } = payload ?? {};
      if (typeof roomId !== 'string' || roomId.trim() === '') return;
      if (!socket.rooms.has(roomId)) return;
      if (typeof senderId !== 'string' || senderId.trim() === '') return;
      const name = sanitiseSenderName(senderName);
      socket.to(roomId).emit('TYPING', { senderId: senderId.trim(), senderName: name });
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

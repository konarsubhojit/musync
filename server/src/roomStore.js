'use strict';

/**
 * TTL applied to every room key in Redis (4 hours).
 * Prevents memory leaks from stale/abandoned rooms.
 */
const ROOM_TTL_SECONDS = 14400;

/**
 * @typedef {{ id: string, title: string }} VideoRef
 */

/**
 * @typedef {object} RoomData
 * @property {string}               roomId        - The room identifier.
 * @property {string|null}          hostId        - Socket ID of the room host.
 * @property {'PLAYING'|'PAUSED'}   playbackState - Current playback state.
 * @property {boolean}              isPlaying     - Convenience alias derived from playbackState.
 * @property {number}               positionMs    - Last known playback position (ms).
 * @property {VideoRef|null}        currentVideo  - Currently playing video, or null.
 * @property {VideoRef[]}           queue         - Upcoming video queue.
 * @property {number}               updatedAt     - Wall-clock timestamp of last update (ms).
 */

// ── Schema validation ─────────────────────────────────────────────────────────

/**
 * Validates a VideoRef object `{ id: string, title: string }`.
 * @param {unknown} v
 * @returns {boolean}
 */
function isVideoRef(v) {
  return (
    v !== null &&
    typeof v === 'object' &&
    typeof v.id === 'string' && v.id.trim() !== '' &&
    typeof v.title === 'string' && v.title.trim() !== ''
  );
}

/**
 * Validates room data against the canonical schema before it is persisted.
 *
 * Throws a `TypeError` with a descriptive message when validation fails.
 *
 * @param {unknown} data - The object to validate.
 * @returns {RoomData}   - The validated data, typed as RoomData.
 * @throws {TypeError}
 */
function validateRoomData(data) {
  if (data === null || typeof data !== 'object') {
    throw new TypeError('RoomData must be a non-null object');
  }

  const { roomId, hostId, playbackState, isPlaying, positionMs, currentVideo, queue, updatedAt } = data;

  if (typeof roomId !== 'string' || roomId.trim() === '') {
    throw new TypeError('RoomData.roomId must be a non-empty string');
  }

  if (hostId !== null && typeof hostId !== 'string') {
    throw new TypeError('RoomData.hostId must be a string or null');
  }

  if (playbackState !== 'PLAYING' && playbackState !== 'PAUSED') {
    throw new TypeError("RoomData.playbackState must be 'PLAYING' or 'PAUSED'");
  }

  if (typeof isPlaying !== 'boolean') {
    throw new TypeError('RoomData.isPlaying must be a boolean');
  }

  // isPlaying must be consistent with playbackState.
  if (isPlaying !== (playbackState === 'PLAYING')) {
    throw new TypeError('RoomData.isPlaying must be consistent with playbackState');
  }

  if (typeof positionMs !== 'number' || !Number.isFinite(positionMs) || positionMs < 0) {
    throw new TypeError('RoomData.positionMs must be a non-negative finite number');
  }

  if (currentVideo !== null && !isVideoRef(currentVideo)) {
    throw new TypeError(
      'RoomData.currentVideo must be null or an object with non-empty string fields id and title',
    );
  }

  if (!Array.isArray(queue) || queue.some((item) => !isVideoRef(item))) {
    throw new TypeError(
      'RoomData.queue must be an array of objects with non-empty string fields id and title',
    );
  }

  if (typeof updatedAt !== 'number' || !Number.isFinite(updatedAt) || updatedAt < 0) {
    throw new TypeError('RoomData.updatedAt must be a non-negative finite number');
  }

  return /** @type {RoomData} */ (data);
}

// ── Store factory ─────────────────────────────────────────────────────────────

/**
 * Creates a room store backed by Upstash Redis when the environment variables
 * `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN` are present, or by
 * an in-memory `Map` as a fallback (used in local development and tests).
 *
 * Every `setRoom` call validates the data and (when using Redis) applies a
 * 4-hour TTL so stale rooms are automatically cleaned up.
 *
 * @returns {{ getRoom: Function, setRoom: Function, deleteRoom: Function }}
 */
function createRoomStore() {
  const url = process.env.UPSTASH_REDIS_REST_URL;
  const token = process.env.UPSTASH_REDIS_REST_TOKEN;

  if (url && token) {
    const { Redis } = require('@upstash/redis');
    const redis = new Redis({ url, token });

    return {
      /**
       * Fetches room data from Redis, validates the shape, and returns it.
       * Returns `null` (and logs a warning) when the stored value is absent
       * or fails schema validation, so callers never receive malformed data.
       * @param {string} roomId
       * @returns {Promise<RoomData|null>}
       */
      async getRoom(roomId) {
        const raw = await redis.get(`room:${roomId}`);
        if (raw === null || raw === undefined) return null;
        try {
          return validateRoomData(raw);
        } catch (err) {
          console.warn(`[roomStore] Redis key room:${roomId} contains invalid data — ignoring:`, err.message);
          return null;
        }
      },

      /**
       * Validates and stores room data in Redis with a 4-hour TTL.
       * @param {string}   roomId
       * @param {RoomData} roomData
       * @returns {Promise<void>}
       */
      async setRoom(roomId, roomData) {
        validateRoomData(roomData);
        await redis.set(`room:${roomId}`, roomData, { ex: ROOM_TTL_SECONDS });
      },

      /**
       * Deletes the room key from Redis.
       * @param {string} roomId
       * @returns {Promise<void>}
       */
      async deleteRoom(roomId) {
        await redis.del(`room:${roomId}`);
      },
    };
  }

  // ── In-memory fallback (no Redis credentials configured) ──────────────────
  const store = new Map();

  return {
    async getRoom(roomId) {
      return store.get(roomId) ?? null;
    },
    async setRoom(roomId, roomData) {
      validateRoomData(roomData);
      store.set(roomId, roomData);
    },
    async deleteRoom(roomId) {
      store.delete(roomId);
    },
  };
}

module.exports = { createRoomStore, validateRoomData, isVideoRef, ROOM_TTL_SECONDS };

'use strict';

/**
 * TTL applied to every room key in Redis (4 hours).
 * Prevents memory leaks from stale/abandoned rooms.
 */
const ROOM_TTL_SECONDS = 14400;

/**
 * @typedef {object} RoomData
 * @property {string}                          roomId        - The room identifier.
 * @property {string|null}                     hostId        - Socket ID of the room host.
 * @property {'PLAYING'|'PAUSED'}              playbackState - Current playback state.
 * @property {boolean}                         isPlaying     - Convenience alias for playbackState.
 * @property {number}                          positionMs    - Last known playback position (ms).
 * @property {{ id: string, title: string }|null} currentVideo - Currently playing video.
 * @property {Array<{ id: string, title: string }>} queue     - Upcoming video queue.
 * @property {number}                          updatedAt     - Wall-clock timestamp of last update.
 */

/**
 * Creates a room store backed by Upstash Redis when the environment variables
 * `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN` are present, or by
 * an in-memory `Map` as a fallback (used in local development and tests).
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
       * Fetches room data from Redis.
       * @param {string} roomId
       * @returns {Promise<RoomData|null>}
       */
      async getRoom(roomId) {
        return redis.get(`room:${roomId}`);
      },

      /**
       * Stores room data in Redis with a 4-hour TTL.
       * @param {string}   roomId
       * @param {RoomData} roomData
       * @returns {Promise<void>}
       */
      async setRoom(roomId, roomData) {
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
      store.set(roomId, roomData);
    },
    async deleteRoom(roomId) {
      store.delete(roomId);
    },
  };
}

module.exports = { createRoomStore, ROOM_TTL_SECONDS };

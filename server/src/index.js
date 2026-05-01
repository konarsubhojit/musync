'use strict';

const { createApp } = require('./server');

const PORT = process.env.PORT ?? 3000;

const { httpServer } = createApp();

httpServer.listen(PORT, () => {
  console.log(`[musync-server] listening on port ${PORT}`);
});

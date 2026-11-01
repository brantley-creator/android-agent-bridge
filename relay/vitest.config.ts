import { defineWorkersConfig } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig({
  test: {
    poolOptions: {
      workers: {
        isolatedStorage: false,
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: {
          bindings: {
            RELAY_ADMIN_TOKEN: "test-admin-token",
          },
        },
      },
    },
  },
});

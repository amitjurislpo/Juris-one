import "dotenv/config";
import { defineConfig, env } from "prisma/config";

export default defineConfig({
  schema: "prisma/schema.prisma",
  migrations: {
    path: "prisma/migrations",
    // react-server condition: the seed reuses server modules (payroll close, leave accrual)
    seed: "tsx --conditions=react-server prisma/seed.ts",
  },
  datasource: {
    url: env("DATABASE_URL"),
  },
});

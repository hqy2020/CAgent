import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "path";
export default defineConfig({
    plugins: [react()],
    test: {
        environment: "node",
        globals: true
    },
    resolve: {
        alias: {
            "@": path.resolve(__dirname, "./src")
        }
    },
    server: {
        host: '0.0.0.0',
        port: 5173,
        proxy: {
            "/api": {
                target: process.env.VITE_API_TARGET || "http://localhost:8080",
                changeOrigin: true,
                secure: false,
                ws: true
            }
        }
    }
});

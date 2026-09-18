import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Vite baut die React-App zu statischen Dateien (dist/). Die kopiert das
// Dockerfile des web-gateway in dessen Jar. Mehr Konfiguration braucht es nicht.
export default defineConfig({
  plugins: [react()],
})

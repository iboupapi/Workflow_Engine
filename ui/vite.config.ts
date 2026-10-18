import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Console de test du moteur (Phase 2).
// Backend attendu sur http://localhost:8080 en profil `dev` (jetons + CORS).
// Les appels partent en direct (CORS gere cote backend) : pas de proxy.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
  },
})

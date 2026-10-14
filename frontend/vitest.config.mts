import { defineConfig } from 'vitest/config';

// Pruebas de la lógica pura de la interfaz (reglas espejo, renderizado seguro de manuales).
// El pool de hilos evita el arranque lento de procesos en Windows.
export default defineConfig({
  test: {
    include: ['src/**/*.spec.ts'],
    environment: 'node',
    pool: 'threads',
  },
});

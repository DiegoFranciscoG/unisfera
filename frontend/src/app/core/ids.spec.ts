import { describe, expect, it } from 'vitest';
import { newId } from './ids';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('newId', () => {
  it('usa randomUUID cuando el navegador lo ofrece', () => {
    expect(newId()).toMatch(UUID_V4);
  });

  it('genera un UUID v4 válido sin randomUUID (HTTP fuera de localhost o navegadores antiguos)', () => {
    const original = globalThis.crypto.randomUUID;
    Object.defineProperty(globalThis.crypto, 'randomUUID', { value: undefined, configurable: true });
    try {
      const ids = new Set(Array.from({ length: 200 }, () => newId()));
      expect(ids.size).toBe(200);
      for (const id of ids) {
        expect(id).toMatch(UUID_V4);
      }
    } finally {
      Object.defineProperty(globalThis.crypto, 'randomUUID', { value: original, configurable: true });
    }
  });
});

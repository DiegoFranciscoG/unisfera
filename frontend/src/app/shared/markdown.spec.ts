import { describe, expect, it } from 'vitest';
import { renderMarkdown, slugify } from './markdown';

describe('renderMarkdown', () => {
  it('escapa HTML: el contenido de un manual nunca inyecta etiquetas', () => {
    const html = renderMarkdown('Hola <script>alert(1)</script> y <img src=x onerror=alert(1)>');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('<img');
    expect(html).toContain('&lt;script&gt;');
  });

  it('respeta las anclas explícitas {#rn-05} para enlazar desde los errores', () => {
    expect(renderMarkdown('## Cupos {#rn-05}')).toBe('<h2 id="rn-05">Cupos</h2>');
  });

  it('genera listas, negritas y código', () => {
    const html = renderMarkdown('- **uno**\n- `dos`\n\n1. tres');
    expect(html).toContain('<ul>\n<li><strong>uno</strong></li>\n<li><code>dos</code></li>\n</ul>');
    expect(html).toContain('<ol>\n<li>tres</li>\n</ol>');
  });

  it('solo enlaza rutas internas o https', () => {
    expect(renderMarkdown('[ok](/ayuda/pagos)')).toContain('<a href="/ayuda/pagos">ok</a>');
    expect(renderMarkdown('[mal](javascript:alert(1))')).not.toContain('href="javascript');
  });
});

describe('tablas', () => {
  it('convierte una tabla de Markdown con encabezado y escapa sus celdas', () => {
    const html = renderMarkdown('| Rol | Uso |\n|---|---|\n| **Docente** | <b>notas</b> |');
    expect(html).toContain('<th>Rol</th><th>Uso</th>');
    expect(html).toContain('<td><strong>Docente</strong></td><td>&lt;b&gt;notas&lt;/b&gt;</td>');
  });
});

describe('slugify', () => {
  it('quita tildes y signos', () => {
    expect(slugify('¿Cómo me matriculo?')).toBe('como-me-matriculo');
  });
});

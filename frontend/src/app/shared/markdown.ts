/**
 * Renderizador mínimo de Markdown para los manuales (títulos, listas, negrita, código, enlaces,
 * citas y anclas {#rn-05}). Escapa todo el HTML de entrada: no hay inyección posible.
 */
export function renderMarkdown(md: string): string {
  const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const inline = (s: string) =>
    escape(s)
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/`(.+?)`/g, '<code>$1</code>')
      .replace(/\[([^\]]+)\]\((\/[^)\s]*|https:\/\/[^)\s]*)\)/g, '<a href="$2">$1</a>');
  const out: string[] = [];
  let list: 'ul' | 'ol' | null = null;
  const closeList = () => {
    if (list) {
      out.push(`</${list}>`);
      list = null;
    }
  };
  let table: string[][] | null = null;
  const closeTable = () => {
    if (table) {
      const [head, ...body] = table;
      out.push(
        '<div class="table-wrap"><table><thead><tr>' + head.map((c) => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>' +
          body.map((r) => '<tr>' + r.map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>').join('') + '</tbody></table></div>',
      );
      table = null;
    }
  };
  for (const raw of md.split('\n')) {
    const line = raw.trimEnd();
    if (/^\|.*\|$/.test(line.trim())) {
      closeList();
      const cells = line.trim().slice(1, -1).split('|').map((c) => c.trim());
      if (!cells.every((c) => /^:?-{3,}:?$/.test(c))) {
        (table ??= []).push(cells);
      }
      continue;
    }
    closeTable();
    const heading = /^(#{1,3})\s+(.*?)(?:\s*\{#([a-z0-9-]+)\})?$/.exec(line);
    if (heading) {
      closeList();
      const level = heading[1].length;
      const id = heading[3] ?? slugify(heading[2]);
      out.push(`<h${level} id="${id}">${inline(heading[2])}</h${level}>`);
      continue;
    }
    if (/^!\[/.test(line)) {
      continue;
    }
    const bullet = /^[-*]\s+(.*)$/.exec(line);
    const numbered = /^\d+\.\s+(.*)$/.exec(line);
    const item = bullet ?? numbered;
    if (item) {
      const kind = bullet ? 'ul' : 'ol';
      if (list !== kind) {
        closeList();
        out.push(`<${kind}>`);
        list = kind;
      }
      out.push(`<li>${inline(item[1])}</li>`);
      continue;
    }
    closeList();
    if (line.startsWith('> ')) {
      out.push(`<blockquote>${inline(line.slice(2))}</blockquote>`);
    } else if (line.trim() !== '') {
      out.push(`<p>${inline(line)}</p>`);
    }
  }
  closeList();
  closeTable();
  return out.join('\n');
}

export function slugify(text: string): string {
  return text
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '');
}

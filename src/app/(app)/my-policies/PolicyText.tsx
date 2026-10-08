/** A policy's text: paragraphs separated by blank lines, line breaks kept. Plain text only — never rendered as HTML. */
export function PolicyText({ body }: { body: string }) {
  return (
    <div style={{ fontSize: 14, lineHeight: 1.65 }}>
      {body.split(/\n{2,}/).map((p, i) => <p key={i} style={{ margin: "0 0 12px", whiteSpace: "pre-wrap" }}>{p}</p>)}
    </div>
  );
}

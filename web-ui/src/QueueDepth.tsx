import { useEffect, useState } from 'react'

/** Was das Gateway unter /api/admin/queue liefert (siehe QueueStats.java). */
type QueueStats = {
  queueName: string
  messages: number
  consumers: number
  publishRatePerSecond: number
  ackRatePerSecond: number
}

/** So oft wird nachgefragt. RabbitMQ selbst aktualisiert die Raten alle 5 Sekunden. */
const REFRESH_MILLISECONDS = 1000

/** Unter dieser Grösse wird der Balken nie skaliert, damit 3 Nachrichten nicht wie ein Stau aussehen. */
const MINIMUM_SCALE = 1000

/** Zahlen mit Tausendertrennzeichen, wie in der Schweiz üblich: 12'345. */
function formatNumber(value: number): string {
  return Math.round(value).toLocaleString('de-CH')
}

/**
 * Der Balken mit der Tiefe der Queue chat.persist (PLANUNG.md, Abschnitt 4.2).
 *
 * Läuft der load-generator und kommt der batch-writer nicht nach, wächst
 * der Balken. Schaltet man mit "--scale batch-writer=N" Instanzen dazu,
 * sinkt er — vor der Klasse, ohne Neustart.
 *
 * Der Balken ist relativ zum höchsten Wert, der seit dem Öffnen der Seite
 * gesehen wurde. So sieht man einen Stau auch dann, wenn er klein ist.
 */
export function QueueDepth() {
  const [stats, setStats] = useState<QueueStats | null>(null)
  const [highestSeen, setHighestSeen] = useState(MINIMUM_SCALE)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    async function loadStats() {
      const response = await fetch('/api/admin/queue')
      if (!response.ok) {
        setError('Queue-Tiefe nicht verfügbar (HTTP ' + response.status + ')')
        return
      }
      const loadedStats: QueueStats = await response.json()
      setError(null)
      setStats(loadedStats)
      setHighestSeen((currentHighest) => Math.max(currentHighest, loadedStats.messages))
    }

    loadStats()
    const timer = window.setInterval(loadStats, REFRESH_MILLISECONDS)
    return () => window.clearInterval(timer)
  }, [])

  if (error !== null) {
    return <p role="status">{error}</p>
  }
  if (stats === null) {
    return <p>Lade Queue-Tiefe …</p>
  }

  const fillPercent = Math.min(100, (stats.messages / highestSeen) * 100)

  return (
    <section aria-label="Queue-Tiefe">
      <h2>Queue {stats.queueName}</h2>
      <div style={{ border: '1px solid #888', height: '1.5rem', width: '100%', maxWidth: '40rem' }}>
        <div style={{ background: '#d9822b', height: '100%', width: fillPercent + '%' }} />
      </div>
      <p>
        <strong>{formatNumber(stats.messages)}</strong> Nachrichten warten ·{' '}
        {stats.consumers} batch-writer verbunden · rein {formatNumber(stats.publishRatePerSecond)}/s · in die
        Datenbank {formatNumber(stats.ackRatePerSecond)}/s
      </p>
    </section>
  )
}

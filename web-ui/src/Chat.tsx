import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'

/** Eine zugestellte Nachricht (siehe ChatMessage.java im web-gateway). */
type ChatMessage = {
  id: string
  roomId: string
  senderId: string
  senderName: string
  content: string
  sentAt: string
}

/** Alles, was das Gateway über den WebSocket schickt (siehe ServerEvent.java). */
type ServerEvent =
  | { type: 'message'; payload: ChatMessage }
  | { type: 'accepted'; payload: { id: string; sentAt: string } }
  | { type: 'error'; payload: string }

/** Was der Chat von aussen braucht: welcher Raum offen ist. */
type ChatProps = {
  roomId: string
  roomName: string
}

/**
 * Fügt eine Nachricht in die Liste ein und sortiert nach dem Zeitstempel
 * des Servers. Bei mehreren chat-service-Instanzen ist die Reihenfolge der
 * Zustellung nicht garantiert (PLANUNG.md, offener Punkt 3), deshalb
 * sortiert der Client. Eine doppelt zugestellte Nachricht wird verworfen.
 */
function addMessage(currentMessages: ChatMessage[], incoming: ChatMessage): ChatMessage[] {
  for (const existing of currentMessages) {
    if (existing.id === incoming.id) {
      return currentMessages
    }
  }
  const nextMessages = [...currentMessages, incoming]
  nextMessages.sort((first, second) => Date.parse(first.sentAt) - Date.parse(second.sentAt))
  return nextMessages
}

/** Fügt den ganzen Verlauf ein, Nachricht für Nachricht, mit derselben Regel wie oben. */
function addHistory(currentMessages: ChatMessage[], history: ChatMessage[]): ChatMessage[] {
  let nextMessages = currentMessages
  for (const message of history) {
    nextMessages = addMessage(nextMessages, message)
  }
  return nextMessages
}

/**
 * Baut die WebSocket-Adresse aus der Adresse der Seite: gleicher Rechner,
 * gleicher Port. Der Raum steht in der Adresse, damit das Gateway weiss,
 * welche Nachrichten diese Verbindung bekommen soll.
 */
function buildSocketUrl(roomId: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return protocol + '//' + window.location.host + '/ws/chat?roomId=' + encodeURIComponent(roomId)
}

/**
 * Der Chat eines Raums: eine WebSocket-Verbindung zum Gateway, der Verlauf
 * aus der Datenbank, eine Liste, ein Eingabefeld.
 *
 * Die eigene Nachricht wird NICHT sofort in die Liste geschrieben. Sie
 * erscheint erst, wenn sie über den Zustellweg zurückkommt, genau wie bei
 * allen anderen. So sieht man, dass sie wirklich durch das System lief.
 */
export function Chat({ roomId, roomName }: ChatProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [draft, setDraft] = useState('')
  const [connected, setConnected] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const socketRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    // Wird die Komponente abgebaut, bevor der Verlauf da ist, darf die
    // Antwort den Zustand nicht mehr ändern.
    let active = true

    // Der Verlauf kommt aus der Datenbank: Gateway -> chat-service -> SELECT.
    async function loadHistory() {
      const response = await fetch('/api/rooms/' + encodeURIComponent(roomId) + '/messages')
      if (!active) {
        return
      }
      if (!response.ok) {
        setHistoryError('Verlauf konnte nicht geladen werden (HTTP ' + response.status + ')')
        return
      }
      const history: ChatMessage[] = await response.json()
      if (!active) {
        return
      }
      setMessages((currentMessages) => addHistory(currentMessages, history))
    }

    const socket = new WebSocket(buildSocketUrl(roomId))
    socketRef.current = socket

    // Reihenfolge mit Absicht: ERST die Verbindung, DANN der Verlauf.
    // Umgekehrt ginge eine Nachricht verloren, die genau zwischen beiden
    // Schritten geschrieben wird. So kann sie höchstens doppelt kommen,
    // und Doppelte sortiert addMessage über die ID aus.
    socket.onopen = () => {
      setConnected(true)
      loadHistory()
    }
    socket.onclose = () => setConnected(false)

    socket.onmessage = (event: MessageEvent<string>) => {
      const serverEvent: ServerEvent = JSON.parse(event.data)
      if (serverEvent.type === 'message') {
        const incoming = serverEvent.payload
        setMessages((currentMessages) => addMessage(currentMessages, incoming))
      }
      if (serverEvent.type === 'accepted') {
        setSendError(null)
      }
      if (serverEvent.type === 'error') {
        setSendError(serverEvent.payload)
      }
    }

    // Aufräumen, wenn die Komponente verschwindet (z.B. Raumwechsel). Die
    // Handler kommen zuerst weg, damit die alte Verbindung nichts mehr am
    // Zustand ändert.
    return () => {
      active = false
      socket.onopen = null
      socket.onclose = null
      socket.onmessage = null
      socket.close()
    }
  }, [roomId])

  function sendDraft(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const content = draft.trim()
    if (content === '') {
      return
    }

    const socket = socketRef.current
    if (socket === null || socket.readyState !== WebSocket.OPEN) {
      setSendError('Nachricht nicht gesendet: keine Verbindung.')
      return
    }

    // Nur Raum und Text. Den Absender kennt das Gateway aus dem Login.
    const outgoing = { roomId: roomId, content: content }
    socket.send(JSON.stringify(outgoing))
    setDraft('')
  }

  return (
    <section>
      <h2>{roomName}</h2>
      {!connected && <p role="status">Keine Verbindung zum Server. Seite neu laden.</p>}
      {historyError !== null && <p role="alert">{historyError}</p>}

      <ul>
        {messages.map((message) => (
          <li key={message.id}>
            <time dateTime={message.sentAt}>{new Date(message.sentAt).toLocaleTimeString()}</time>{' '}
            <strong>{message.senderName}:</strong> {message.content}
          </li>
        ))}
      </ul>

      <form onSubmit={sendDraft}>
        <input
          type="text"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="Nachricht schreiben …"
          aria-label="Nachricht"
          maxLength={2000}
        />
        <button type="submit" disabled={!connected}>
          Senden
        </button>
      </form>

      {/* Es gibt keinen Puffer auf dem Sendeweg: ein Fehler muss sichtbar sein. */}
      {sendError !== null && <p role="alert">{sendError}</p>}
    </section>
  )
}

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'

// Einstiegspunkt: React übernimmt das Element mit der id "root" aus index.html.
const rootElement = document.getElementById('root')
if (rootElement === null) {
  throw new Error('Element #root fehlt in index.html')
}

const root = createRoot(rootElement)
root.render(
  <StrictMode>
    <App />
  </StrictMode>,
)

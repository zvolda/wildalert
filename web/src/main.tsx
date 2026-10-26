import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'

const container = document.getElementById('root')
if (!container) throw new Error('No #root element in index.html')

// StrictMode double-invokes renders and effects in development to surface impure components.
// If you see something happen twice while developing, this is why -- it does not happen in the
// production build.
createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

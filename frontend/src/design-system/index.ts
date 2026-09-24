/**
 * Ponto único de importação do Design System.
 *
 * As features importam SOMENTE daqui (`src/design-system`), nunca de uma lib visual
 * diretamente. Assim, migrar para o Diana (ou outro DS corporativo) significa
 * reimplementar esta fachada mantendo a mesma API — sem tocar nas telas.
 */
import './tokens.css';
import './components.css';

export { Alert, Badge, Button, Card, Money, Select, Stack, Tabs, TextField, formatMoney } from './components';

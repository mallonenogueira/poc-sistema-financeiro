import { useCallback, useEffect, useState } from 'react';

type State<T> = { data?: T; error?: Error; loading: boolean };

/** Carrega dados assíncronos com estados de loading/erro e função de recarga. */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[]) {
  const [state, setState] = useState<State<T>>({ loading: true });

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps);

  const reload = useCallback(() => {
    setState((previous) => ({ ...previous, loading: true }));
    run()
      .then((data) => setState({ data, loading: false }))
      .catch((error: Error) => setState({ error, loading: false }));
  }, [run]);

  useEffect(reload, [reload]);

  return { ...state, reload, setData: (data: T) => setState({ data, loading: false }) };
}

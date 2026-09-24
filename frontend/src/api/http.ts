/** Erro de API no formato RFC 9457 (ProblemDetail) devolvido pelos microsserviços. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly title: string,
    readonly detail?: string,
  ) {
    super(detail ?? title);
    this.name = 'ApiError';
  }
}

export async function http<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...init.headers },
  });

  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(response.status, problem.title ?? response.statusText, problem.detail);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

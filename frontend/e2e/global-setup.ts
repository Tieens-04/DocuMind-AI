interface ActuatorHealthResponse {
  status?: string;
}

interface OllamaTagsResponse {
  models?: Array<{ name?: string; model?: string }>;
}

async function fetchWithTimeout(url: string, timeoutMs: number): Promise<Response> {
  const response = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} ${response.statusText}`);
  }
  return response;
}

async function fetchJsonWithTimeout<T>(url: string, timeoutMs: number): Promise<T> {
  const response = await fetchWithTimeout(url, timeoutMs);
  return (await response.json()) as T;
}

export default async function globalSetup(): Promise<void> {
  const frontendBaseUrl = (process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:3002').replace(/\/+$/, '');
  const backendBaseUrl = (process.env.PLAYWRIGHT_API_URL || 'http://localhost:8081').replace(/\/+$/, '');
  const ollamaBaseUrl = (process.env.PLAYWRIGHT_OLLAMA_URL || 'http://localhost:11434').replace(/\/+$/, '');
  const requiredEmbeddingModel = process.env.OLLAMA_EMBEDDING_MODEL || 'bge-m3';

  try {
    await fetchWithTimeout(`${frontendBaseUrl}/login`, 5000);
  } catch (error) {
    throw new Error(
      `[Playwright E2E Preflight] Frontend not reachable at ${frontendBaseUrl}/login. Start the Full-Stack Docker Compose environment first ("docker compose up -d --build"). Cause: ${String(error)}`,
    );
  }

  try {
    const health = await fetchJsonWithTimeout<ActuatorHealthResponse>(
      `${backendBaseUrl}/actuator/health`,
      5000,
    );
    if (health.status !== 'UP') {
      throw new Error(`Backend actuator status is "${health.status ?? 'UNKNOWN'}" (expected "UP")`);
    }
  } catch (error) {
    throw new Error(
      `[Playwright E2E Preflight] Backend healthcheck failed at ${backendBaseUrl}/actuator/health. Ensure Docker Compose backend + postgres + redis + minio are healthy. Cause: ${String(error)}`,
    );
  }

  try {
    const tags = await fetchJsonWithTimeout<OllamaTagsResponse>(`${ollamaBaseUrl}/api/tags`, 5000);
    const modelNames = (tags.models ?? []).map((m) => (m.name || m.model || '').toLowerCase());
    const hasEmbeddingModel = modelNames.some((name) =>
      name.includes(requiredEmbeddingModel.toLowerCase()),
    );
    if (!hasEmbeddingModel) {
      throw new Error(
        `Model "${requiredEmbeddingModel}" not found in Ollama tags: [${modelNames.join(', ')}]`,
      );
    }
  } catch (error) {
    throw new Error(
      `[Playwright E2E Preflight] Native Ollama with "${requiredEmbeddingModel}" is required at ${ollamaBaseUrl}/api/tags. Start Ollama and pull "${requiredEmbeddingModel}" before running E2E. Cause: ${String(error)}`,
    );
  }
}

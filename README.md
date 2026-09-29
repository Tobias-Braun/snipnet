# Snipnet

AI-assisted roundnet footage cutter. Drop in raw tripod footage, get the rallies cut out, fix them on a real
timeline, export.

- Desktop app: Kotlin / Compose Multiplatform (`apps/client`)
- Landing page: React + GSAP (`apps/web`)
- API: Fastify (`services/api`)
- AI inference: Python worker (`services/inference`, `ml/`), runs only in the backend

See [docs/PLAN.md](docs/PLAN.md) for the architecture and roadmap and [docs/api.md](docs/api.md) for the API contract.

export async function mount(ctx) {
  await ctx.loadLogsPage(ctx.force);
}

export async function unmount(ctx) {
  ctx.stopLogPolling();
}

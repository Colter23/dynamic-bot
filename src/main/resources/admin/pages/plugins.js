export async function mount(ctx) {
  await ctx.loadPlugins(ctx.force);
}

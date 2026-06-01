export async function mount(ctx) {
  await ctx.loadConfigs(ctx.force);
}

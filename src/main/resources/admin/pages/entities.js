export async function mount(ctx) {
  await ctx.loadEntities(ctx.force);
}

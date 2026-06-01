export async function mount(ctx) {
  await ctx.loadSystem(ctx.force);
}

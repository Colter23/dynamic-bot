export async function mount(ctx) {
  await ctx.loadSubscriptions(ctx.force);
}

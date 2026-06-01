export async function mount(ctx) {
  await ctx.loadPlatformLogins(ctx.force);
}

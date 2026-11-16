import { useEffect, useState } from 'react';
import { canUseChatgptAuth, chatgptAuth, type ChatgptModel, type ChatgptStatus } from '../../native/chatgptAuth';
import { chatgptPlanProvider, isChatgptPlanProvider } from '../../engines/chatgptPlan';
import { useRuntimeStore } from '../../stores/runtimeStore';
import { useI18n } from '../../i18n/useI18n';

export function ChatgptSubscriptionCard() {
  const { t } = useI18n();
  const available = canUseChatgptAuth();
  const [status, setStatus] = useState<ChatgptStatus>({ accounts: [], activeRef: '' });
  const [models, setModels] = useState<ChatgptModel[]>([]);
  const [model, setModel] = useState('');
  const [busy, setBusy] = useState('');
  const [message, setMessage] = useState('');
  const [storageError, setStorageError] = useState(false);
  const account = status.accounts.find(item => item.ref === status.activeRef);

  useEffect(() => {
    if (!available) return;
    let mounted = true;
    chatgptAuth.status().then(value => { if (mounted) setStatus(value); })
      .catch(error => { if (mounted) { setStorageError(true); setMessage(error instanceof Error ? error.message : t('chatgpt.error')); } });
    return () => { mounted = false; };
  }, [available]);

  function useModel(selected: string) {
    if (!selected) return;
    const runtime = useRuntimeStore.getState();
    const existing = runtime.providers.find(isChatgptPlanProvider);
    if (existing) {
      runtime.updateProvider(existing.id, { model: selected });
      runtime.setActiveProvider(existing.id);
    } else runtime.importProvider(chatgptPlanProvider(selected));
    setMessage(t('chatgpt.usingModel', { model: selected }));
  }

  function clearModel() {
    setModels([]); setModel('');
    const runtime = useRuntimeStore.getState();
    runtime.providers.filter(isChatgptPlanProvider).forEach(provider => runtime.updateProvider(provider.id, { model: '' }));
  }

  async function loadModels(activate: boolean) {
    const result = await chatgptAuth.models();
    setModels(result.models);
    const current = useRuntimeStore.getState().providers.find(isChatgptPlanProvider)?.model;
    const chosen = result.models.find(item => item.id === current)?.id ?? result.models[0]?.id ?? '';
    setModel(chosen);
    if (!chosen) setMessage(t('chatgpt.noModels'));
    else if (activate) useModel(chosen);
  }

  async function run(kind: string, action: () => Promise<void>) {
    setBusy(kind); setMessage('');
    try { await action(); }
    catch (error) { setMessage(error instanceof Error ? error.message : t('chatgpt.error')); }
    finally { setBusy(''); }
  }

  async function connected(next: ChatgptStatus) {
    setStatus(next); clearModel();
    const active = next.accounts.find(item => item.ref === next.activeRef);
    if (active?.planGranted && active.connected) {
      setBusy('models');
      await loadModels(true);
    }
  }

  return <section className="provider-stack" aria-label={t('chatgpt.title')}>
    <h3>{t('chatgpt.title')}</h3>
    <p>{t('chatgpt.description')}</p>
    {!available ? <p>{t('chatgpt.androidOnly')}</p> : <>
      {storageError && <button className="btn-secondary" type="button" disabled={Boolean(busy)} onClick={() => {
        if (window.confirm(t('chatgpt.resetConfirm'))) void run('reset', async () => {
          setStatus(await chatgptAuth.resetLocalConnections()); clearModel(); setStorageError(false);
          setMessage(t('chatgpt.revocationUnconfirmed'));
        });
      }}>{t('chatgpt.reset')}</button>}
      {status.accounts.length > 0 && <label>
        {t('chatgpt.account')}
        <select disabled={Boolean(busy)} value={status.activeRef} onChange={event => {
          const ref = event.target.value;
          if (ref) void run('select', async () => connected(await chatgptAuth.select(ref)));
        }}>
          <option value="">{t('chatgpt.selectAccount')}</option>
          {status.accounts.map(item => <option key={item.ref} value={item.ref}>{item.email || item.subject}</option>)}
        </select>
      </label>}
      {account && <>
        <p>{t('chatgpt.workspace', { registration: account.registration })}</p>
        <p>{account.connected ? t('chatgpt.connected') : t('chatgpt.disconnected')} · {account.planGranted ? t('chatgpt.granted') : t('chatgpt.notGranted')}</p>
      </>}
      <div className="provider-inline-actions">
        <button className="btn-secondary" type="button" disabled={Boolean(busy)} onClick={() => void run('login', async () => connected(await chatgptAuth.login()))}>Continue with ChatGPT</button>
        {account && <button className="btn-secondary" type="button" disabled={Boolean(busy)} onClick={() => void run('login', async () => connected(await chatgptAuth.login(account.ref)))}>{t('chatgpt.reauthorize')}</button>}
        {busy === 'login' && <button className="btn-secondary" type="button" onClick={() => void chatgptAuth.cancelLogin().catch(() => setMessage(t('chatgpt.error')))}>{t('chatgpt.cancel')}</button>}
        {account?.connected && <button className="btn-secondary" type="button" disabled={Boolean(busy)} onClick={() => void run('disconnect', async () => {
          const result = await chatgptAuth.disconnect();
          clearModel(); setStatus(await chatgptAuth.status());
          setMessage(result.revocationConfirmed ? t('chatgpt.signedOut') : t('chatgpt.revocationUnconfirmed'));
        })}>{t('chatgpt.disconnect')}</button>}
      </div>
      {account?.connected && account.planGranted && <>
        <button className="btn-secondary" type="button" disabled={Boolean(busy)} onClick={() => void run('models', async () => loadModels(false))}>{t('chatgpt.refreshModels')}</button>
        {models.length > 0 && <label>{t('chatgpt.model')}
          <select value={model} disabled={Boolean(busy)} onChange={event => setModel(event.target.value)}>
            {models.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
          </select>
        </label>}
        {model && <button className="btn-secondary" type="button" disabled={Boolean(busy)} onClick={() => useModel(model)}>{t('chatgpt.useModel')}</button>}
      </>}
      {busy && <p role="status">{t(busy === 'login' ? 'chatgpt.authorizing' : busy === 'models' ? 'chatgpt.loadingModels' : 'chatgpt.busy')}</p>}
    </>}
    {message && <p role="status">{message}</p>}
  </section>;
}

export const manifest = {
  id: 'poco-contacts-actions',
  name: 'Contacts Actions',
  version: '3.1',
  author: 'Backtalk Contributor',
  minApiVersion: 1,
  testedApiVersion: 4,
  description: 'Brings direct custom accessibility actions (Call, Video Call, Delete, Edit, Message, Block) to Recents and Contacts tabs on Poco/MIUI devices.',
  apps: ['com.android.contacts'],
  permissions: ['screen', 'actions', 'input', 'speech', 'system'],
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(predicate, timeoutMs = 1500, intervalMs = 60) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const result = await predicate();
      if (result) return result;
    } catch (e) {
      // Ignore transition errors
    }
    await sleep(intervalMs);
  }
  return null;
}

async function clickNodeOrParent(node) {
  if (!node) return false;
  if (node.clickable) {
    return await node.click();
  }
  let p = node.parent;
  while (p) {
    if (p.clickable) {
      return await p.click();
    }
    p = p.parent;
  }
  return await node.click();
}

function findAncestor(node, predicate) {
  let curr = node;
  while (curr) {
    if (predicate(curr)) return curr;
    curr = curr.parent;
  }
  return null;
}

function getActionableTarget(node) {
  if (!node) return null;

  // 1. Recents Tab: item container is com.android.contacts:id/content_layout
  const recentRow = findAncestor(node, (n) => {
    const id = n.id || '';
    return id.includes('content_layout');
  });
  if (recentRow) {
    return { type: 'recent', node: recentRow };
  }

  // 2. Contacts Tab: item is contact_list_item_view or QuickContactBadge
  const contactRow = findAncestor(node, (n) => {
    const id = n.id || '';
    const cls = n.className || '';
    return id.includes('contact_list_item_view') || cls.includes('ContactListItemView');
  });
  if (contactRow) {
    return { type: 'contact', node: contactRow };
  }

  if (node.contentDescription && node.contentDescription.startsWith('Quick contact for')) {
    return { type: 'contact', node };
  }

  if (node.className && node.className.endsWith('QuickContactBadge')) {
    return { type: 'contact', node };
  }

  return null;
}

// Helper: Open call history details for a recent item
async function openRecentDetails(row) {
  let detailsBtn = row.find({ id: 'secondary_action_view' }) ||
                   row.find({ id: 'secondary_action_iv' }) ||
                   row.find({ contentDescription: 'Call history details' });

  if (!detailsBtn && row.parent) {
    detailsBtn = row.parent.find({ id: 'secondary_action_view' }) ||
                 row.parent.find({ contentDescription: 'Call history details' });
  }

  if (detailsBtn) {
    await clickNodeOrParent(detailsBtn);
  } else {
    await clickNodeOrParent(row);
  }
}

// Helper: Open contact details (SubActivity) for a contact row
// In MIUI, the OnClickListener is attached to the QuickContactBadge child, NOT the parent ViewGroup!
async function openContactDetails(row) {
  let badge = null;
  if (row.className && row.className.endsWith('QuickContactBadge')) {
    badge = row;
  } else {
    badge = row.find({ className: 'QuickContactBadge' });
    if (!badge && row.children) {
      for (const child of row.children) {
        if (child.className && child.className.endsWith('QuickContactBadge')) {
          badge = child;
          break;
        }
      }
    }
  }

  if (badge) {
    await clickNodeOrParent(badge);
  } else {
    // If badge not found, try any clickable child, then fallback to row
    let clicked = false;
    const children = row.children || [];
    for (const child of children) {
      if (child.clickable) {
        await clickNodeOrParent(child);
        clicked = true;
        break;
      }
    }
    if (!clicked) {
      await clickNodeOrParent(row);
    }
  }
}

// ==========================================
// Recents Tab Handlers (Working as expected)
// ==========================================

async function handleRecentCall(row) {
  await clickNodeOrParent(row);
}

async function handleRecentVideoCall(row) {
  await openRecentDetails(row);

  const videoBtn = await waitFor(() => {
    return (
      backtalk.screen.find({ contentDescription: 'Video call' }) ||
      backtalk.screen.find({ text: 'Video call' }) ||
      backtalk.screen.find({ textContains: 'Video call' })
    );
  }, 1500);

  if (videoBtn) {
    await clickNodeOrParent(videoBtn);
  } else {
    backtalk.speak('Video call not available');
    await backtalk.system.back();
  }
}

async function handleRecentMessage(row) {
  await openRecentDetails(row);

  const msgBtn = await waitFor(() => {
    return (
      backtalk.screen.find({ contentDescription: 'Text message' }) ||
      backtalk.screen.find({ text: 'Text message' }) ||
      backtalk.screen.find({ textContains: 'message' })
    );
  }, 1500);

  if (msgBtn) {
    await clickNodeOrParent(msgBtn);
  } else {
    backtalk.speak('Message button not found');
    await backtalk.system.back();
  }
}

async function handleRecentEdit(row) {
  await openRecentDetails(row);

  const editBtn = await waitFor(() => {
    return (
      backtalk.screen.find({ id: 'bar_edit' }) ||
      backtalk.screen.find({ id: 'll_edit' }) ||
      backtalk.screen.find({ contentDescription: 'Edit contact' }) ||
      backtalk.screen.find({ text: 'Edit contact' })
    );
  }, 1500);

  if (editBtn) {
    await clickNodeOrParent(editBtn);
  } else {
    backtalk.speak('Edit button not found');
    await backtalk.system.back();
  }
}

async function handleRecentLongPressOption(row, optionText) {
  await row.longClick();

  const menuItem = await waitFor(() => {
    return (
      backtalk.screen.find({ contentDescription: optionText }) ||
      backtalk.screen.find({ text: optionText }) ||
      backtalk.screen.find({ textContains: optionText })
    );
  }, 1200);

  if (menuItem) {
    await clickNodeOrParent(menuItem);
  } else {
    await backtalk.system.back();
    backtalk.speak(`${optionText} not found`);
  }
}

// ==========================================
// Contacts Tab Handlers
// ==========================================

async function handleContactCall(row) {
  await openContactDetails(row);

  const dialBtn = await waitFor(() => {
    return (
      backtalk.screen.find({ id: 'first_action_button' }) ||
      backtalk.screen.find({ contentDescription: 'Dial' }) ||
      backtalk.screen.find({ text: 'Dial' })
    );
  }, 1500);

  if (dialBtn) {
    await clickNodeOrParent(dialBtn);
  } else {
    backtalk.speak('Dial button not found');
    await backtalk.system.back();
  }
}

async function handleContactVideoCall(row) {
  await openContactDetails(row);

  const videoBtn = await waitFor(() => {
    return (
      backtalk.screen.find({ contentDescription: 'Video call' }) ||
      backtalk.screen.find({ text: 'Video call' }) ||
      backtalk.screen.find({ textContains: 'Video call' })
    );
  }, 1500);

  if (videoBtn) {
    await clickNodeOrParent(videoBtn);
  } else {
    backtalk.speak('Video call not available for this contact');
    await backtalk.system.back();
  }
}

async function handleContactMessage(row) {
  await openContactDetails(row);

  const msgBtn = await waitFor(() => {
    return (
      backtalk.screen.find({ contentDescription: 'Text message' }) ||
      backtalk.screen.find({ text: 'Text message' }) ||
      backtalk.screen.find({ textContains: 'message' })
    );
  }, 1500);

  if (msgBtn) {
    await clickNodeOrParent(msgBtn);
  } else {
    backtalk.speak('Message button not found');
    await backtalk.system.back();
  }
}

async function handleContactEdit(row) {
  await openContactDetails(row);

  const editBtn = await waitFor(() => {
    return (
      backtalk.screen.find({ id: 'bar_edit' }) ||
      backtalk.screen.find({ id: 'll_edit' }) ||
      backtalk.screen.find({ contentDescription: 'Edit contact' }) ||
      backtalk.screen.find({ text: 'Edit contact' })
    );
  }, 1500);

  if (editBtn) {
    await clickNodeOrParent(editBtn);
  } else {
    backtalk.speak('Edit button not found');
    await backtalk.system.back();
  }
}

async function handleContactMoreOption(row, optionText) {
  // 1. Open contact details (SubActivity)
  await openContactDetails(row);

  // 2. Click 'More' button (3 dots menu)
  const moreBtn = await waitFor(() => {
    return (
      backtalk.screen.find({ id: 'action_bar_more' }) ||
      backtalk.screen.find({ contentDescription: 'More' }) ||
      backtalk.screen.find({ text: 'More' })
    );
  }, 1500);

  if (!moreBtn) {
    backtalk.speak('More options button not found');
    await backtalk.system.back();
    return;
  }

  await clickNodeOrParent(moreBtn);

  // 3. Click requested option: 'Delete contact', 'Block', 'Share', 'Place on Home screen'
  const optionBtn = await waitFor(() => {
    return (
      backtalk.screen.find({ text: optionText }) ||
      backtalk.screen.find({ contentDescription: optionText }) ||
      backtalk.screen.find({ textContains: optionText })
    );
  }, 1200);

  if (optionBtn) {
    await clickNodeOrParent(optionBtn);
  } else {
    await backtalk.system.back();
    backtalk.speak(`${optionText} not found`);
  }
}

// ==========================================
// Backtalk Actions Hook
// ==========================================

backtalk.onActions((node) => {
  const target = getActionableTarget(node);
  if (!target) return [];

  if (target.type === 'recent') {
    return [
      {
        title: 'Call',
        run: () => handleRecentCall(target.node),
      },
      {
        title: 'Video Call',
        run: () => handleRecentVideoCall(target.node),
      },
      {
        title: 'Send message',
        run: () => handleRecentMessage(target.node),
      },
      {
        title: 'Edit',
        run: () => handleRecentEdit(target.node),
      },
      {
        title: 'Delete',
        run: () => handleRecentLongPressOption(target.node, 'Delete'),
      },
      {
        title: 'Block',
        run: () => handleRecentLongPressOption(target.node, 'Flag'),
      },
    ];
  }

  if (target.type === 'contact') {
    return [
      {
        title: 'Call',
        run: () => handleContactCall(target.node),
      },
      {
        title: 'Video Call',
        run: () => handleContactVideoCall(target.node),
      },
      {
        title: 'Send message',
        run: () => handleContactMessage(target.node),
      },
      {
        title: 'Edit Contact',
        run: () => handleContactEdit(target.node),
      },
      {
        title: 'Delete Contact',
        run: () => handleContactMoreOption(target.node, 'Delete contact'),
      },
      {
        title: 'Block',
        run: () => handleContactMoreOption(target.node, 'Block'),
      },
      {
        title: 'Share',
        run: () => handleContactMoreOption(target.node, 'Share'),
      },
    ];
  }

  return [];
});

console.log('Poco Contacts Actions v3.1 loaded');

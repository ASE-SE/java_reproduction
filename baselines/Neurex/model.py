"""
Neurex model class definition.

Copied from the original training notebook at
https://github.com/anonymous-000000/neurex/blob/main/training_notebook.ipynb
(cell #26). The repo only publishes training code; this file is what we need
to instantiate the model and let `from_pretrained(checkpoint-30768)` populate
all three task heads (token / cls / excep) from pytorch_model.bin.

Three heads share the same RoBERTa encoder:
- token_classifier: per-token BIO over {O, B-Try, I-Try}      → locate try-block span
- cls_classifier:   [CLS]-position binary                      → does this method need a try-catch
- excep_classifier: per-statement 30-class multi-label         → which exceptions to catch

Statement-level aggregation in training uses scatter_sum over per-token hidden
states with an `excep_index` tensor (token → statement-id). Inference does the
same — see wrapper_neurex.py for how excep_index is built from \\n positions.
"""
import torch
import torch.nn as nn
from transformers import RobertaConfig
from transformers.models.roberta.modeling_roberta import (
    RobertaModel,
    RobertaPreTrainedModel,
)
from torch_scatter import scatter


class CodebertForExcepPrediction(RobertaPreTrainedModel):
    config_class = RobertaConfig
    # transformers 5.x compatibility: from_pretrained() touches this attribute
    # during meta-device init. Original class (transformers 4.x) didn't need it.
    all_tied_weights_keys = {}

    def __init__(self, config):
        super().__init__(config)
        self.num_labels = config.num_labels
        self.num_cls_labels = config.num_cls_labels

        self.roberta = RobertaModel(config, add_pooling_layer=False)
        self.dropout = nn.Dropout(config.hidden_dropout_prob)
        self.token_classifier = nn.Linear(config.hidden_size, config.num_labels)
        self.cls_classifier = nn.Linear(config.hidden_size, config.num_cls_labels)
        self.excep_classifier = nn.Linear(config.hidden_size, config.num_excep_labels)

        self.init_weights()

    def forward(
        self,
        input_ids=None,
        attention_mask=None,
        token_type_ids=None,
        labels=None,
        label_cls=None,
        excep_ids=None,
        excep_count=None,
        excep_index=None,
        **kwargs,
    ):
        outputs = self.roberta(
            input_ids,
            attention_mask=attention_mask,
            token_type_ids=token_type_ids,
            **kwargs,
        )
        sequence_output = self.dropout(outputs.last_hidden_state)
        token_logits = self.token_classifier(sequence_output)
        cls_logits = self.cls_classifier(sequence_output[:, 0])

        loss_fct = nn.CrossEntropyLoss()
        loss_fct2 = nn.MultiLabelSoftMarginLoss(reduction="none")
        cls_loss = None
        if label_cls is not None:
            cls_loss = loss_fct(cls_logits, label_cls)

        stmt_loss = None
        excep_loss = 0.0
        if label_cls is not None:
            mask = label_cls.clone().detach().bool().requires_grad_(False)
            if (labels is not None) and torch.any(mask):
                token_logits_masked = token_logits[mask]
                labels_masked = labels[mask]
                stmt_loss = loss_fct(
                    token_logits_masked.view(-1, self.num_labels),
                    labels_masked.view(-1),
                )
                sequence_output_masked = sequence_output[mask]
                excep_ids_masked = excep_ids[mask]
                excep_count_masked = excep_count[mask]
                excep_index_masked = excep_index[mask]
                for out, ids, count, index in zip(
                    sequence_output_masked,
                    excep_ids_masked,
                    excep_count_masked,
                    excep_index_masked,
                ):
                    h = scatter(out, index, dim=0, reduce="sum")[:count, :]
                    excep_loss += torch.sum(
                        loss_fct2(self.excep_classifier(h), ids[:count, :])
                    )

        loss = None
        if cls_loss is not None:
            loss = cls_loss if stmt_loss is None else (
                cls_loss + stmt_loss + excep_loss / mask.sum()
            )
        return {
            "loss": loss,
            "logits": token_logits,
            "cls_logits": cls_logits,
            "last_hidden_state": outputs.last_hidden_state,
            "attentions": outputs.attentions,
            "label_cls": label_cls,
            "labels": labels,
        }


# 30-class exception list, copied from training_notebook.ipynb cell #15.
# Order matters: index in this list = column index in excep_classifier output.
EXCEPTION_LABELS = [
    "ClassCastException",
    "SecurityException",
    "UnsupportedOperationException",
    "NoSuchAlgorithmException",
    "SQLException",
    "IOException",
    "NoSuchMethodException",
    "IllegalArgumentException",
    "NullPointerException",
    "FileNotFoundException",
    "MalformedURLException",
    "InterruptedException",
    "JSONException",
    "UnsupportedEncodingException",
    "com.google.protobuf.InvalidProtocolBufferException",
    "IllegalStateException",
    "IllegalAccessException",
    "URISyntaxException",
    "ExecutionException",
    "InvalidArgumentException",
    "SAXException",
    "NumberFormatException",
    "ClassNotFoundException",
    "RuntimeException",
    "GenericEntityException",
    "InvocationTargetException",
    "ParseException",
    "IndexOutOfBoundsException",
    "InstantiationException",
    "com.google.protobuf.UninitializedMessageException",
]

# BIO tag scheme, copied from training_notebook.ipynb cell #24.
INDEX2TAG = {0: "O", 1: "B-Try", 2: "I-Try"}
TAG2INDEX = {v: k for k, v in INDEX2TAG.items()}
